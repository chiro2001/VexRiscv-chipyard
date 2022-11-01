package vexriscv.demo

import spinal.core._
import spinal.lib.bus.amba3.apb.{Apb3, Apb3Config, Apb3Decoder, Apb3Gpio}
import spinal.lib.bus.amba4.axi.{Axi4CrossbarFactory, Axi4ReadOnly, Axi4Shared, Axi4SharedOnChipRam}
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.simple.{PipelinedMemoryBus, PipelinedMemoryBusConfig, PipelinedMemoryBusToApbBridge}
import spinal.lib.com.jtag.Jtag
import spinal.lib.com.spi.ddr.{Apb3SpiXdrMasterCtrl, SpiXdrMaster}
import spinal.lib.com.uart.{Apb3UartCtrl, Uart, UartCtrlGenerics, UartCtrlInitConfig, UartCtrlMemoryMappedConfig, UartParityType, UartStopType}
import spinal.lib.eda.altera.{InterruptReceiverTag, ResetEmitterTag}
import spinal.lib.io.TriStateArray
import spinal.lib.{BufferCC, master, slave}
import vexriscv.ip.InstructionCacheConfig
import vexriscv.plugin._
import vexriscv.{VexRiscv, VexRiscvConfig, plugin}

import scala.collection.mutable.ArrayBuffer
import scala.language.postfixOps

case class VexChipConfig
(iCacheSize: Int = 0,
 onChipRamSize: BigInt = 64 kB,
 onChipRamHexFile: String = null,
 hardwareBreakpointCount: Int = 2,
 pipelineMainBus: Boolean = false,
 coreFrequency: HertzNumber = 5 MHz,
 pipelineDBus: Boolean = true,
 pipelineApbBridge: Boolean = true,
 cpuPlugins: ArrayBuffer[Plugin[VexRiscv]] = VexChipConfig.defaultPlugins(bigEndian = false)) {
}
// object VexChipConfig {
//   def apply(iCacheSize: BigInt, onChipRamSize: BigInt,)
// }

import vexriscv.demo.VexInterfaceConfig._

object VexChipConfig {
  def defaultPlugins(bigEndian: Boolean) = ArrayBuffer( //DebugPlugin added by the toplevel
    new IBusSimplePlugin(
      resetVector = resetVector,
      cmdForkOnSecondStage = true,
      cmdForkPersistence = true,
      prediction = NONE,
      catchAccessFault = false,
      compressedGen = false,
      bigEndian = false
    ),
    new DBusSimplePlugin(
      catchAddressMisaligned = false,
      catchAccessFault = false,
      earlyInjection = false,
      bigEndian = bigEndian
    ),
    // // new CsrPlugin(CsrPluginConfig.smallest(mtvecInit = 0x80000020l)),
    // new CsrPlugin(CsrPluginConfig.linuxFull(0x80000020L)),
    new CsrPlugin(CsrPluginConfig.all(0x80000020L)),
    // new CsrPlugin(CsrPluginConfig.openSbi(0, 66)),
    new DecoderSimplePlugin(
      catchIllegalInstruction = false
    ),
    new RegFilePlugin(
      regFileReadyKind = plugin.SYNC,
      zeroBoot = false
    ),
    new IntAluPlugin,
    new SrcPlugin(
      separatedAddSub = false,
      executeInsertion = false
    ),
    new LightShifterPlugin,
    new HazardSimplePlugin(
      bypassExecute = false,
      bypassMemory = false,
      bypassWriteBack = false,
      bypassWriteBackBuffer = false,
      pessimisticUseSrc = false,
      pessimisticWriteRegFile = false,
      pessimisticAddressMatch = false
    ),
    new BranchPlugin(
      earlyBranch = false,
      catchAddressMisaligned = false
    ),
    // new YamlPlugin("cpu0.yaml")
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
    val jtag = slave(Jtag())

    //Peripherals IO
    val uart = master(Uart())
  }.setName("")

  val resetP = ~io.reset

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
    val systemClkResetCounter = Reg(UInt(6 bits)) init (0)
    when(systemClkResetCounter =/= U(systemClkResetCounter.range -> true)) {
      systemClkResetCounter := systemClkResetCounter + 1
      mainClkResetUnbuffered := True
    }
    when(BufferCC(resetP)) {
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

    val bigEndianDBus = config.cpuPlugins.exists { case plugin: DBusSimplePlugin => plugin.bigEndian case _ => false }

    //Arbiter of the cpu dBus/iBus to drive the mainBus
    //Priority to dBus, !! cmd transactions can change on the fly !!
    val mainBusArbiter = new MuraxMasterArbiter(pipelinedMemoryBusConfig, bigEndianDBus)

    //Instanciate the CPU
    val cpu = new VexRiscv(
      config = VexRiscvConfig(
        plugins = cpuPlugins += new DebugPlugin(debugClockDomain, hardwareBreakpointCount)
      )
    )

    //Checkout plugins used to instanciate the CPU to connect them to the SoC
    val timerInterrupt = False
    val externalInterrupt = False
    for (plugin <- cpu.plugins) plugin match {
      case plugin: IBusSimplePlugin =>
        mainBusArbiter.io.iBus.cmd <> plugin.iBus.cmd
        mainBusArbiter.io.iBus.rsp <> plugin.iBus.rsp
      case plugin: DBusSimplePlugin => {
        if (!pipelineDBus)
          mainBusArbiter.io.dBus <> plugin.dBus
        else {
          mainBusArbiter.io.dBus.cmd << plugin.dBus.cmd.halfPipe()
          mainBusArbiter.io.dBus.rsp <> plugin.dBus.rsp
        }
      }
      case plugin: CsrPlugin => {
        plugin.externalInterrupt := externalInterrupt
        plugin.timerInterrupt := timerInterrupt
      }
      case plugin: DebugPlugin => plugin.debugClockDomain {
        resetCtrl.systemReset setWhen (RegNext(plugin.io.resetOut))
        io.jtag <> plugin.io.bus.fromJtag()
      }
      case _ =>
    }


    //****** MainBus slaves ********
    val mainBusMapping = ArrayBuffer[(PipelinedMemoryBus, SizeMapping)]()
    val ram = new MuraxPipelinedMemoryBusRam(
      onChipRamSize = onChipRamSize,
      onChipRamHexFile = onChipRamHexFile,
      pipelinedMemoryBusConfig = pipelinedMemoryBusConfig,
      bigEndian = bigEndianDBus
    )
    mainBusMapping += ram.io.bus -> (0x80000000L, onChipRamSize)

    val apbBridge = new PipelinedMemoryBusToApbBridge(
      apb3Config = Apb3Config(
        addressWidth = 20,
        dataWidth = 32
      ),
      pipelineBridge = pipelineApbBridge,
      pipelinedMemoryBusConfig = pipelinedMemoryBusConfig
    )
    mainBusMapping += apbBridge.io.pipelinedMemoryBus -> (0x00000000L, 1 MB)


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
        baudrate = 115200,
        dataLength = 7, //7 => 8 bits
        parity = UartParityType.NONE,
        stop = UartStopType.ONE
      ),
      busCanWriteClockDividerConfig = false,
      busCanWriteFrameConfig = false,
      txFifoDepth = 16,
      rxFifoDepth = 16
    )

    val uartCtrl = Apb3UartCtrl(uartCtrlConfig)
    uartCtrl.io.uart <> io.uart
    externalInterrupt setWhen (uartCtrl.io.interrupt)
    apbMapping += uartCtrl.io.apb -> (0x10000, 4 kB)

    val timer = new MuraxApb3Timer()
    timerInterrupt setWhen (timer.io.interrupt)
    apbMapping += timer.io.apb -> (0x20000, 4 kB)

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
  def run(config: VexChipConfig, name: String = "VexChip"): Unit = {
    SpinalVerilog(VexChip(config).setDefinitionName(name))
  }

  def main(args: Array[String]): Unit = {
    val name = if (args.isEmpty) "VexChip" else args(0)
    run(VexChipConfig.default, name = name)
  }
}