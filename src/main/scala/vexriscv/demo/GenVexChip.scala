package vexriscv.demo

import spinal.core._
import spinal.lib.bus.amba3.apb.{Apb3, Apb3Config, Apb3Decoder}
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.simple.{PipelinedMemoryBus, PipelinedMemoryBusConfig, PipelinedMemoryBusToApbBridge}
import spinal.lib.com.jtag.Jtag
import spinal.lib.com.uart._
import spinal.lib.{BufferCC, master, slave}
import vexriscv.demo.GenVexChip._
import vexriscv.plugin._
import vexriscv.{VexRiscv, VexRiscvConfig, plugin}

import java.io.File
import scala.collection.mutable.ArrayBuffer
import scala.language.postfixOps
import scala.sys.process._

object VexDefaultConfig {
  val resetVector = 0x10000L
}

case class VexChipConfig
(iCacheSize: Int = 0,
 onChipRamSize: BigInt = 64 KiB,
 onChipRamBinaryFile: String = null,
 hardwareBreakpointCount: Int = 3,
 pipelineMainBus: Boolean = false,
 coreFrequency: HertzNumber = 5 MHz,
 pipelineDBus: Boolean = true,
 pipelineApbBridge: Boolean = true,
 debug: Boolean = true,
 resetVector: BigInt = vexriscv.demo.VexDefaultConfig.resetVector,
 negativeReset: Boolean = false,
 uartBaudRate: Int = 115200,
 replaceMemoryIP: Boolean = false,
 cpuPlugins: ArrayBuffer[Plugin[VexRiscv]] = VexChipConfig.defaultPlugins(bigEndian = false)) {
}

object VexChipConfig {
  def defaultPlugins(bigEndian: Boolean) = ArrayBuffer( //DebugPlugin added by the toplevel
    new DBusSimplePlugin(
      // catchAddressMisaligned = true,
      catchAddressMisaligned = false,
      // catchAccessFault = true,
      catchAccessFault = false,
      earlyInjection = false,
      bigEndian = bigEndian
    ),
    new StaticMemoryTranslatorPlugin(
      ioRange = _ (31 downto 28) === 0xF
    ),
    // // new CsrPlugin(CsrPluginConfig.smallest(mtvecInit = 0x80000020l)),
    // new CsrPlugin(CsrPluginConfig.linuxFull(0x80000020L)),
    // new CsrPlugin(CsrPluginConfig.small(0x000000E0L)),
    // new CsrPlugin(CsrPluginConfig.linuxFull(0x800000E0L)),
    // new CsrPlugin(CsrPluginConfig.all(0x80000020L)),
    new CsrPlugin(CsrPluginConfig.all(0x800000E0L)),
    // new CsrPlugin(CsrPluginConfig.openSbi(0, 66)),
    new DecoderSimplePlugin(
      catchIllegalInstruction = true
    ),
    new RegFilePlugin(
      regFileReadyKind = plugin.SYNC,
      zeroBoot = false
    ),
    new IntAluPlugin,
    new SrcPlugin(
      separatedAddSub = false,
      executeInsertion = true
    ),
    // new LightShifterPlugin,
    new FullBarrelShifterPlugin(earlyInjection = true),
    new HazardSimplePlugin(
      bypassExecute = true,
      bypassMemory = true,
      bypassWriteBack = true,
      bypassWriteBackBuffer = true,
      pessimisticUseSrc = false,
      pessimisticWriteRegFile = false,
      pessimisticAddressMatch = false
    ),
    // new MulPlugin,
    // new MulDivIterativePlugin(genMul = false, genDiv = true, mulUnrollFactor = 1, divUnrollFactor = 1, dhrystoneOpt = false),
    new MulPlugin,
    new DivPlugin,
    new BranchPlugin(
      earlyBranch = false,
      catchAddressMisaligned = false
    ),
    new YamlPlugin("VexChip.yaml")
  )

  def default: VexChipConfig = default()

  def default(bigEndian: Boolean = false) = VexChipConfig(
    cpuPlugins = defaultPlugins(bigEndian))

  def fast = {
    val config = default

    //Replace HazardSimplePlugin to get datapath bypass
    config.cpuPlugins(config.cpuPlugins.indexWhere(_.isInstanceOf[HazardSimplePlugin])) = new HazardSimplePlugin(
      bypassExecute = true,
      bypassMemory = true,
      bypassWriteBack = true,
      bypassWriteBackBuffer = true
    )
    //    config.cpuPlugins(config.cpuPlugins.indexWhere(_.isInstanceOf[LightShifterPlugin])) = new FullBarrelShifterPlugin()

    config
  }
}

class VexChip(config: VexChipConfig) extends Component {

  import config._

  val io = new Bundle {
    //Clocks / reset
    val reset = in Bool()
    val sys_clock = in Bool()

    //Main components IO
    var jtag: Jtag = _

    //Peripherals IO
    val uart = master(Uart())
  }.setName("")

  // val resetP = if (negativeReset) ~io.reset else io.reset
  val resetInput = io.reset
  val resetInputNeg = ~io.reset

  val resetCtrlClockDomain = ClockDomain(
    clock = io.sys_clock,
    config = ClockDomainConfig(
      resetKind = BOOT
    )
  )

  val resetCtrl = new ClockingArea(resetCtrlClockDomain) {
    val mainClkResetUnbuffered = False

    //Implement an counter to keep the reset axiResetOrder high 64 cycles
    // Also this counter will automatically do a reset when the system boot.
    val systemClkResetCounter = Reg(UInt(6 bits)) init 0
    when(systemClkResetCounter =/= U(systemClkResetCounter.range -> true)) {
      systemClkResetCounter := systemClkResetCounter + 1
      mainClkResetUnbuffered := True
    }
    when(BufferCC(if (!negativeReset) resetInput else resetInputNeg)) {
      systemClkResetCounter := 0
    }

    //Create all reset used later in the design
    val mainClkReset = RegNext(mainClkResetUnbuffered)
    val systemReset = RegNext(mainClkResetUnbuffered)
  }


  val systemClockDomain = ClockDomain(
    clock = io.sys_clock,
    reset = resetCtrl.systemReset,
    frequency = FixedFrequency(coreFrequency)
  )

  val debugClockDomain = ClockDomain(
    clock = io.sys_clock,
    reset = resetCtrl.mainClkReset,
    frequency = FixedFrequency(coreFrequency)
  )

  val system = new ClockingArea(systemClockDomain) {
    val pipelinedMemoryBusConfig = PipelinedMemoryBusConfig(
      addressWidth = 32,
      dataWidth = 32
    )

    if (!cpuPlugins.exists(p => p.isInstanceOf[IBusSimplePlugin]))
      cpuPlugins += new IBusSimplePlugin(
        resetVector = resetVector,
        cmdForkOnSecondStage = false,
        cmdForkPersistence = false,
        prediction = DYNAMIC_TARGET,
        historyRamSizeLog2 = 8,
        catchAccessFault = true,
        compressedGen = false,
        bigEndian = false
      )
    if (!cpuPlugins.exists(p => p.isInstanceOf[SimpleFormalPlugin]))
      cpuPlugins += new SimpleFormalPlugin
    require(cpuPlugins.exists(p => p.isInstanceOf[IBusSimplePlugin]))
    println(s"resetVector = ${resetVector}")

    val bigEndianDBus = config.cpuPlugins.exists { case plugin: DBusSimplePlugin => plugin.bigEndian case _ => false }

    //Arbiter of the cpu dBus/iBus to drive the mainBus
    //Priority to dBus, !! cmd transactions can change on the fly !!
    val mainBusArbiter = new MuraxMasterArbiter(pipelinedMemoryBusConfig, bigEndianDBus)

    //Instanciate the CPU
    val cpu = new VexRiscv(
      config = VexRiscvConfig(
        plugins = if (debug) cpuPlugins += new DebugPlugin(debugClockDomain, hardwareBreakpointCount) else cpuPlugins
      )
    )

    //Checkout plugins used to instanciate the CPU to connect them to the SoC
    val timerInterrupt = False
    val externalInterrupt = False
    for (plugin <- cpu.plugins) plugin match {
      case plugin: IBusSimplePlugin =>
        mainBusArbiter.io.iBus.cmd <> plugin.iBus.cmd
        mainBusArbiter.io.iBus.rsp <> plugin.iBus.rsp
      case plugin: DBusSimplePlugin =>
        if (!pipelineDBus)
          mainBusArbiter.io.dBus <> plugin.dBus
        else {
          mainBusArbiter.io.dBus.cmd << plugin.dBus.cmd.halfPipe()
          mainBusArbiter.io.dBus.rsp <> plugin.dBus.rsp
        }
      case plugin: CsrPlugin => {
        plugin.externalInterrupt := externalInterrupt
        plugin.timerInterrupt := timerInterrupt
      }
      case plugin: DebugPlugin => plugin.debugClockDomain {
        resetCtrl.systemReset setWhen (RegNext(plugin.io.resetOut))
        io.jtag = slave(Jtag()).setName("jtag")
        io.jtag <> plugin.io.bus.fromJtag()
      }
      case plugin: SimpleFormalPlugin => {
        println("Enabled SimpleFormalPlugin")
        plugin.rvfi <> master(SimpleRvfiPort()).setName("rvfi")
      }
      case _ =>
    }


    //****** MainBus slaves ********
    val mainBusMapping = ArrayBuffer[(PipelinedMemoryBus, SizeMapping)]()
    val ram = if (!replaceMemoryIP)
      new VexChipPipelinedMemoryBusRam(
        onChipRamSize = onChipRamSize,
        onChipRamBinaryFile = onChipRamBinaryFile,
        pipelinedMemoryBusConfig = pipelinedMemoryBusConfig,
        bigEndian = bigEndianDBus
      ) else new VexChipPipelinedMemoryBusDRM(
      onChipRamSize = onChipRamSize,
      onChipRamBinaryFile = onChipRamBinaryFile,
      pipelinedMemoryBusConfig = pipelinedMemoryBusConfig,
      bigEndian = bigEndianDBus
    )
    // mainBusMapping += ram.io.bus -> (0x80000000L, onChipRamSize)
    mainBusMapping += ram.getBus -> (resetVector, onChipRamSize)

    val apbBridge = new PipelinedMemoryBusToApbBridge(
      apb3Config = Apb3Config(
        addressWidth = 20,
        dataWidth = 32
      ),
      pipelineBridge = pipelineApbBridge,
      pipelinedMemoryBusConfig = pipelinedMemoryBusConfig
    )
    mainBusMapping += apbBridge.io.pipelinedMemoryBus -> (0x10000000L, 1 MiB)


    //******** APB peripherals *********
    val apbMapping = ArrayBuffer[(Apb3, SizeMapping)]()

    val uartCtrlConfig = UartCtrlMemoryMappedConfig(
      uartCtrlConfig = UartCtrlGenerics(
        dataWidthMax = 8,
        clockDividerWidth = 20,
        preSamplingSize = 1,
        samplingSize = 3,
        postSamplingSize = 1
      ),
      initConfig = UartCtrlInitConfig(
        baudrate = uartBaudRate,
        dataLength = 7, //7 => 8 bits
        parity = UartParityType.NONE,
        stop = UartStopType.ONE
      ),
      busCanWriteClockDividerConfig = false,
      busCanWriteFrameConfig = false,
      txFifoDepth = 1024,
      // txFifoDepth = 16,
      rxFifoDepth = 16
    )

    val uartCtrl = Apb3UartCtrl(uartCtrlConfig)
    uartCtrl.io.uart <> io.uart
    externalInterrupt setWhen (uartCtrl.io.interrupt)
    apbMapping += uartCtrl.io.apb -> (0x00000, 4 KiB)

    val timer = new MuraxApb3Timer()
    timerInterrupt setWhen (timer.io.interrupt)
    apbMapping += timer.io.apb -> (0x20000, 4 KiB)

    //******** Memory mappings *********
    val apbDecoder = Apb3Decoder(
      master = apbBridge.io.apb,
      slaves = apbMapping.toSeq
    )

    val mainBusDecoder = new Area {
      val logic = new MuraxPipelinedMemoryBusDecoder(
        master = mainBusArbiter.io.masterBus,
        specification = mainBusMapping.toSeq,
        pipelineMaster = pipelineMainBus
      )
    }
  }
}

object VexChip {
  def apply(config: VexChipConfig) = {
    new VexChip(config)
  }
}

object GenVexChip {
  def makeCoreMark(force: Boolean = true): String = {
    val baseDir = "./software/coremark"
    val binary = new File(s"$baseDir/overlay/coremark.bootrom.bin")
    if (force) {
      // val clean = s"make -C $baseDir clean"
      val clean = s"make -C $baseDir/riscv-coremark clean"
      require(clean.! == 0 && !binary.exists(), "Failed to clean coremark!")
    }
    if (force || !binary.exists()) {
      val make = s"make -C $baseDir/riscv-coremark bootrom"
      require(make.! == 0 && binary.exists(), "Failed to build coremark!")
    }
    binary.getAbsolutePath
  }

  def makeCoreMarkScratch(): String = {
    val baseDir = "./software/coremark/riscv-coremark/scratch"
    val binary = new File(s"$baseDir/../../overlay/coremark.scratch.bin")
    val make = s"make -C $baseDir force"
    require(make.! == 0 && binary.exists(), "Failed to build coremark!")
    binary.getAbsolutePath
  }

  def makeCoreMarkPerf(): String = {
    val baseDir = "./software/coremark/riscv-coremark/perf"
    val binary = new File(s"$baseDir/../../overlay/coremark.perf.bin")
    val make = s"make -C $baseDir"
    require(make.! == 0 && binary.exists(), "Failed to build coremark!")
    binary.getAbsolutePath
  }

  def makeTests(force: Boolean = true): String = {
    val baseDir = "./software/tests"
    val binary = new File(s"$baseDir/start.rv32.img")
    if (force) {
      val clean = s"make -C $baseDir clean"
      require(clean.! == 0 && !binary.exists(), "Failed to clean bootrom!")
    }
    if (force || !binary.exists()) {
      val make = s"make -C $baseDir"
      require(make.! == 0 && binary.exists(), "Failed to build bootrom!")
    }
    binary.getAbsolutePath
  }

  def makeRTThread(force: Boolean = true): String = {
    val baseDir = "./software/rt-thread"
    val bspDir = s"$baseDir/bsp/pgl22g"
    val binary = new File(s"$bspDir/rtthread.bin")
    // if (force) {
    //   val clean = s"scons -C $bspDir --clean"
    //   require(clean.! == 0, "Failed to clean bootrom!")
    // }
    if (force || !binary.exists()) {
      val make = s"scons -C $bspDir"
      require(make.! == 0 && binary.exists(), "Failed to build bootrom!")
    }
    binary.getAbsolutePath
  }

  def run(config: VexChipConfig, name: String = "VexChip"): Unit = {
    SpinalVerilog(VexChip(config).setDefinitionName(name))
  }

  def main(args: Array[String]): Unit = {
    val filename = if (args.length > 1) args(1) else makeCoreMark()
    val name = if (args.isEmpty) "VexChip" else args(0)
    run(VexChipConfig.default.copy(
      onChipRamBinaryFile = filename,
      debug = false,
      // onChipRamSize = 280 KiB,
      coreFrequency = 100 MHz,
      resetVector = 0x80000000L,
      negativeReset = false
    ), name = name)
  }
}

object GenVexChipDebug extends App {
  run(VexChipConfig.default.copy(
    // onChipRamBinaryFile = makeCoreMark(),
    // onChipRamBinaryFile = makeRTThread(),
    onChipRamBinaryFile = makeCoreMarkScratch(),
    debug = true,
    // onChipRamBinaryFile = makeTests(), debug = true,
    // onChipRamSize = 512 KiB,
    // onChipRamSize = 280 KiB,
    // onChipRamSize = 32 MiB,
    // onChipRamSize = 4 MiB,
    onChipRamSize = 64 KiB,
    coreFrequency = 100 MHz,
    uartBaudRate = 921600,
    resetVector = 0x80000000L,
    negativeReset = false,
  ), name = "VexChip")
}

object GenVexChipSynth extends App {
  run(VexChipConfig.default.copy(
    onChipRamBinaryFile = makeCoreMarkScratch(),
    debug = false,
    onChipRamSize = 64 KiB,
    coreFrequency = 50 MHz,
    uartBaudRate = 921600,
    resetVector = 0x80000000L,
    negativeReset = false,
    replaceMemoryIP = true,
  ), name = "VexChip")
}