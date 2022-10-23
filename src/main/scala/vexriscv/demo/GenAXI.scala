package vexriscv.demo

import spinal.core._
import spinal.lib.{BufferCC, master, slave}
import spinal.lib.bus.amba4.axi.{Axi4ReadOnly, Axi4Shared}
import spinal.lib.soc.pinsec.{PinsecTimerCtrl, PinsecTimerCtrlExternal}
import vexriscv.ip.{DataCacheConfig, InstructionCacheConfig}
import vexriscv.plugin._
import vexriscv.{VexRiscv, VexRiscvConfig, plugin}

import scala.collection.mutable.ArrayBuffer
import scala.language.postfixOps


case class VexAXIConfig(axiFrequency: HertzNumber, cpuPlugins: ArrayBuffer[Plugin[VexRiscv]])

object VexAXIConfig {
  def default = {
    val config = VexAXIConfig(
      axiFrequency = 50 MHz,
      cpuPlugins = ArrayBuffer(
        new PcManagerSimplePlugin(0x80000000l, false),
        //          new IBusSimplePlugin(
        //            interfaceKeepData = false,
        //            catchAccessFault = true
        //          ),
        new IBusCachedPlugin(
          resetVector = 0x80000000l,
          prediction = STATIC,
          config = InstructionCacheConfig(
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
          //            askMemoryTranslation = true,
          //            memoryTranslatorPortConfig = MemoryTranslatorPortConfig(
          //              portTlbSize = 4
          //            )
        ),
        //                    new DBusSimplePlugin(
        //                      catchAddressMisaligned = true,
        //                      catchAccessFault = true
        //                    ),
        new DBusCachedPlugin(
          config = new DataCacheConfig(
            cacheSize = 4096,
            bytePerLine = 32,
            wayCount = 1,
            addressWidth = 32,
            cpuDataWidth = 32,
            memDataWidth = 32,
            catchAccessError = true,
            catchIllegal = true,
            catchUnaligned = true
          ),
          memoryTranslatorPortConfig = null
          //            memoryTranslatorPortConfig = MemoryTranslatorPortConfig(
          //              portTlbSize = 6
          //            )
        ),
        new StaticMemoryTranslatorPlugin(
          ioRange = _ (31 downto 28) === 0xF
        ),
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
        new CsrPlugin(
          config = CsrPluginConfig(
            catchIllegalAccess = false,
            mvendorid = null,
            marchid = null,
            mimpid = null,
            mhartid = null,
            misaExtensionsInit = 66,
            misaAccess = CsrAccess.NONE,
            mtvecAccess = CsrAccess.NONE,
            mtvecInit = 0x80000020l,
            mepcAccess = CsrAccess.READ_WRITE,
            mscratchGen = false,
            mcauseAccess = CsrAccess.READ_ONLY,
            mbadaddrAccess = CsrAccess.READ_ONLY,
            mcycleAccess = CsrAccess.NONE,
            minstretAccess = CsrAccess.NONE,
            ecallGen = false,
            wfiGenAsWait = false,
            ucycleAccess = CsrAccess.NONE,
            uinstretAccess = CsrAccess.NONE
          )
        ),
        new YamlPlugin("cpu0.yaml")
      )
    )
    config
  }
}

class VexAXICore(val config: VexAXIConfig) extends Component {

  import config._

  val debug = true
  val interruptCount = 4

  val io = new Bundle {
    //Clocks / reset
    val asyncReset = in Bool()
    val axiClk = in Bool()

    //Peripherals IO
    // val jtag = slave(Jtag())
    // val timerExternal = in(PinsecTimerCtrlExternal())
    val coreInterrupt = in Bool()

    // mem bus
    val iBus = master(Axi4ReadOnly(IBusSimpleBus.getAxi4Config()))
    val dBus = master(Axi4Shared(DBusSimpleBus.getAxi4Config()))
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
    val config = VexRiscvConfig(
      plugins = ArrayBuffer(
        new PcManagerSimplePlugin(0x80000000l, false),
        //          new IBusSimplePlugin(
        //            interfaceKeepData = false,
        //            catchAccessFault = true
        //          ),
        // new IBusCachedPlugin(
        //   resetVector = 0x80000000l,
        //   prediction = STATIC,
        //   config = InstructionCacheConfig(
        //     cacheSize = 4096,
        //     bytePerLine = 32,
        //     wayCount = 1,
        //     addressWidth = 32,
        //     cpuDataWidth = 32,
        //     memDataWidth = 32,
        //     catchIllegalAccess = true,
        //     catchAccessFault = true,
        //     asyncTagMemory = false,
        //     twoCycleRam = true,
        //     twoCycleCache = true
        //   )
        //   //            askMemoryTranslation = true,
        //   //            memoryTranslatorPortConfig = MemoryTranslatorPortConfig(
        //   //              portTlbSize = 4
        //   //            )
        // ),
        new IBusSimplePlugin(
          resetVector = 0x80000000l,
          false, true
        ),
        //                    new DBusSimplePlugin(
        //                      catchAddressMisaligned = true,
        //                      catchAccessFault = true
        //                    ),
        // new DBusCachedPlugin(
        //   config = new DataCacheConfig(
        //     cacheSize = 4096,
        //     bytePerLine = 32,
        //     wayCount = 1,
        //     addressWidth = 32,
        //     cpuDataWidth = 32,
        //     memDataWidth = 32,
        //     catchAccessError = true,
        //     catchIllegal = true,
        //     catchUnaligned = true
        //   ),
        //   memoryTranslatorPortConfig = null
        //   //            memoryTranslatorPortConfig = MemoryTranslatorPortConfig(
        //   //              portTlbSize = 6
        //   //            )
        // ),
        new DBusSimplePlugin(),
        new StaticMemoryTranslatorPlugin(
          ioRange = _ (31 downto 28) === 0xF
        ),
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
        new CsrPlugin(
          config = CsrPluginConfig(
            catchIllegalAccess = false,
            mvendorid = null,
            marchid = null,
            mimpid = null,
            mhartid = null,
            misaExtensionsInit = 66,
            misaAccess = CsrAccess.NONE,
            mtvecAccess = CsrAccess.NONE,
            mtvecInit = 0x80000020l,
            mepcAccess = CsrAccess.READ_WRITE,
            mscratchGen = false,
            mcauseAccess = CsrAccess.READ_ONLY,
            mbadaddrAccess = CsrAccess.READ_ONLY,
            mcycleAccess = CsrAccess.NONE,
            minstretAccess = CsrAccess.NONE,
            ecallGen = false,
            wfiGenAsWait = false,
            ucycleAccess = CsrAccess.NONE,
            uinstretAccess = CsrAccess.NONE
          ))))

    val cpu = new VexRiscv(config)
    var iBus: Axi4ReadOnly = null
    var dBus: Axi4Shared = null
    for (plugin <- config.plugins) plugin match {
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
        dBus = plugin.dBus.toAxi4Shared(true)
      }
      case plugin: CsrPlugin => {
        plugin.externalInterrupt := BufferCC(io.coreInterrupt)
        // TODO: timer interrupt
        // plugin.timerInterrupt := timerCtrl.io.interrupt
        plugin.timerInterrupt := False
      }
      // case plugin: DebugPlugin => debugClockDomain {
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
