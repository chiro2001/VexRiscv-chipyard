package vexriscv.demo

import spinal.core._
import spinal.lib.bus.amba4.axi.{Axi4ReadOnly, Axi4Shared}
import spinal.lib.{BufferCC, master}
import vexriscv.demo.VexAXIConfig._
import vexriscv.ip.{DataCacheConfig, InstructionCacheConfig}
import vexriscv.plugin._
import vexriscv.{VexRiscv, VexRiscvConfig, plugin}

import scala.collection.mutable.ArrayBuffer
import scala.language.postfixOps

object VexInterfaceConfig {
  val useRvfi = true
  val useSimpleIBusExpected = true
  val useSimpleDBusExpected = true
  val debug = false
  val resetVector = 0x10000L

  def useSimpleIBus = if (!useRvfi) useSimpleIBusExpected else true

  def useSimpleDBus = if (!useRvfi) useSimpleDBusExpected else true
}


case class VexAXIConfig(axiFrequency: HertzNumber, cpuPlugins: ArrayBuffer[Plugin[VexRiscv]])

object VexAXIConfig {
  val iBusConfig = InstructionCacheConfig(
    cacheSize = 4096,
    bytePerLine = 32,
    wayCount = 1,
    addressWidth = 32,
    cpuDataWidth = 32,
    memDataWidth = 32,
    catchIllegalAccess = true,
    catchAccessFault = true,
    asyncTagMemory = false,
    twoCycleRam = true,
    twoCycleCache = true
  )

  val dBusConfig = DataCacheConfig(
    cacheSize = 4096,
    bytePerLine = 32,
    wayCount = 1,
    addressWidth = 32,
    cpuDataWidth = 32,
    memDataWidth = 32,
    catchAccessError = true,
    catchIllegal = true,
    catchUnaligned = true
  )

  import VexInterfaceConfig._

  val basePlugins = ArrayBuffer(
    if (!useSimpleIBus) new IBusCachedPlugin(
      resetVector = resetVector,
      prediction = STATIC,
      config = iBusConfig,
      // askMemoryTranslation = true,
      // memoryTranslatorPortConfig = MemoryTranslatorPortConfig(
      //   portTlbSize = 4
      // )
    ) else
      new IBusSimplePlugin(
        resetVector = resetVector,
        false, true
      ),
    if (!useSimpleDBus) new DBusCachedPlugin(
      config = dBusConfig,
      // // memoryTranslatorPortConfig = null,
      // memoryTranslatorPortConfig = MemoryTranslatorPortConfig(
      //   portTlbSize = 6
      // )
    )
    else new DBusSimplePlugin(
      catchAddressMisaligned = true,
      catchAccessFault = true
    ),
    new StaticMemoryTranslatorPlugin(
      ioRange = _ (31 downto 28) === 0xF
    ),
    new DecoderSimplePlugin(
      catchIllegalInstruction = true,
      forceLegalInstructionComputation = true
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
    new FullBarrelShifterPlugin,
    new MulPlugin,
    new DivPlugin,
    new HazardSimplePlugin(
      bypassExecute = true,
      bypassMemory = true,
      bypassWriteBack = true,
      bypassWriteBackBuffer = true,
      pessimisticUseSrc = false,
      pessimisticWriteRegFile = false,
      pessimisticAddressMatch = false
    ),
    new BranchPlugin(
      earlyBranch = false,
      catchAddressMisaligned = true
    ),
  )

  basePlugins.append(new CsrPlugin(
    config = CsrPluginConfig(
      catchIllegalAccess = true,
      mvendorid = 1,
      marchid = 2,
      mimpid = 3,
      mhartid = 0,
      misaExtensionsInit = 0, // raw is 66
      misaAccess = CsrAccess.READ_WRITE,
      mtvecAccess = CsrAccess.READ_WRITE,
      mtvecInit = 0x80000080L,
      mepcAccess = CsrAccess.READ_WRITE,
      mscratchGen = true,
      mcauseAccess = CsrAccess.READ_WRITE,
      mbadaddrAccess = CsrAccess.READ_WRITE,
      mcycleAccess = CsrAccess.READ_WRITE,
      minstretAccess = CsrAccess.READ_WRITE,
      ucycleAccess = CsrAccess.READ_ONLY,
      uinstretAccess = CsrAccess.READ_ONLY,
      wfiGenAsWait = true,
      ecallGen = true,
      xtvecModeGen = false,
      noCsrAlu = false,
      wfiGenAsNop = false,
      ebreakGen = false,
      userGen = true,
      supervisorGen = false,
      sscratchGen = true,
      stvecAccess = CsrAccess.READ_WRITE,
      sepcAccess = CsrAccess.READ_WRITE,
      scauseAccess = CsrAccess.READ_WRITE,
      sbadaddrAccess = CsrAccess.READ_WRITE,
      scycleAccess = CsrAccess.READ_WRITE,
      sinstretAccess = CsrAccess.READ_WRITE,
      satpAccess = CsrAccess.NONE, //Implemented into the MMU plugin
      medelegAccess = CsrAccess.READ_WRITE,
      midelegAccess = CsrAccess.READ_WRITE,
      pipelineCsrRead = false,
      deterministicInteruptionEntry = false
    )
  ))
  if (useRvfi) {
    basePlugins.append(new SimpleFormalPlugin)
  }

  def default =
    new VexAXIConfig(
      50 MHz,
      basePlugins
    )
}

class VexAXICore(val config: VexAXIConfig) extends Component {

  import VexInterfaceConfig._
  import config._

  val interruptCount = 4

  val io = new Bundle {
    //Clocks / reset
    val asyncReset = in Bool()
    val axiClk = in Bool()

    //Peripherals IO
    // val jtag = slave(Jtag())
    // val timerExternal = in(PinsecTimerCtrlExternal())
    val coreInterrupt = in Bool()
    val rvfi = master(new SimpleRvfiPort)

    // mem bus
    val iBus = master(Axi4ReadOnly(if (useSimpleIBus) IBusSimpleBus.getAxi4Config() else iBusConfig.getAxi4Config()))
    val dBus = master(Axi4Shared(if (useSimpleDBus) DBusSimpleBus.getAxi4Config() else dBusConfig.getAxi4SharedConfig()))
  }

  // val timerCtrl = PinsecTimerCtrl()

  val resetCtrlClockDomain = ClockDomain(
    clock = io.axiClk,
    config = ClockDomainConfig(
      resetKind = BOOT
    )
  )

  val resetCtrl = new ClockingArea(resetCtrlClockDomain) {
    val systemResetUnbuffered = False
    //    val coreResetUnbuffered = False

    //Implement an counter to keep the reset axiResetOrder high 64 cycles
    // Also this counter will automaticly do a reset when the system boot.
    val systemResetCounter = Reg(UInt(6 bits)) init (0)
    when(systemResetCounter =/= U(systemResetCounter.range -> true)) {
      systemResetCounter := systemResetCounter + 1
      systemResetUnbuffered := True
    }
    when(BufferCC(io.asyncReset)) {
      systemResetCounter := 0
    }

    //Create all reset used later in the design
    val systemReset = RegNext(systemResetUnbuffered)
    val axiReset = RegNext(systemResetUnbuffered)
    val vgaReset = BufferCC(axiReset)
  }

  val debugClockDomain = ClockDomain(
    clock = io.axiClk,
    reset = resetCtrl.systemReset,
    frequency = FixedFrequency(axiFrequency)
  )

  val core = new Area {
    // val vexConfig = VexRiscvConfig(plugins = if (debug) cpuPlugins ++ Seq(new DebugPlugin(debugClockDomain)) else cpuPlugins)
    val vexConfig = VexRiscvConfig(plugins = cpuPlugins)
    val cpu = new VexRiscv(vexConfig)
    var iBus: Axi4ReadOnly = null
    var dBus: Axi4Shared = null
    for (plugin <- vexConfig.plugins) plugin match {
      case plugin: IBusSimplePlugin => {
        println("iBus use simple AXI")
        iBus = plugin.iBus.toAxi4ReadOnly()
      }
      case plugin: IBusCachedPlugin => {
        println("iBus use cached AXI")
        iBus = plugin.iBus.toAxi4ReadOnly()
      }
      case plugin: DBusSimplePlugin => {
        println("dBus use simple AXI")
        dBus = plugin.dBus.toAxi4Shared()
      }
      case plugin: DBusCachedPlugin => {
        println("dBus use cached AXI")
        dBus = plugin.dBus.toAxi4Shared(stageCmd = true)
      }
      case plugin: SimpleFormalPlugin => {
        println("Enabled SimpleFormalPlugin")
        plugin.rvfi <> io.rvfi
      }
      case plugin: CsrPlugin => {
        plugin.externalInterrupt := BufferCC(io.coreInterrupt)
        // TODO: timer interrupt
        // plugin.timerInterrupt := timerCtrl.io.interrupt
        plugin.timerInterrupt := False
      }
      // case plugin: DebugPlugin => plugin.debugClockDomain {
      //   resetCtrl.axiReset setWhen (RegNext(plugin.io.resetOut))
      //   io.jtag <> plugin.io.bus.fromJtag()
      // }
      case _ =>
    }

    io.iBus <> iBus
    io.dBus <> dBus
  }
}

object GenAXI extends App {
  val config = SpinalConfig()
  config.generateVerilog({
    val toplevel = new VexAXICore(VexAXIConfig.default)
    toplevel
  })
}
