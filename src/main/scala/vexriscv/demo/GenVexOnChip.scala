package vexriscv.demo

import spinal.core._
import spinal.lib.bus.amba3.apb.{Apb3, Apb3Config, Apb3Decoder, Apb3Gpio}
import spinal.lib.bus.amba4.axi.{Axi4CrossbarFactory, Axi4ReadOnly, Axi4Shared, Axi4SharedOnChipRam}
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.simple.{PipelinedMemoryBus, PipelinedMemoryBusConfig, PipelinedMemoryBusToApbBridge}
import spinal.lib.com.jtag.Jtag
import spinal.lib.com.spi.ddr.{Apb3SpiXdrMasterCtrl, SpiXdrMaster, SpiXdrMasterCtrl, SpiXdrParameter}
import spinal.lib.com.uart.{Apb3UartCtrl, Uart, UartCtrlGenerics, UartCtrlInitConfig, UartCtrlMemoryMappedConfig, UartParityType, UartStopType}
import spinal.lib.io.TriStateArray
import spinal.lib.{BufferCC, master, slave}
import vexriscv.{VexRiscv, VexRiscvConfig, plugin}
import vexriscv.plugin._

import scala.collection.mutable.ArrayBuffer
import scala.language.postfixOps

case class VexOnChipConfig
(onChipRamSize: BigInt,
 onChipRamHexFile: String,
 hardwareBreakpointCount: Int,
 cpuPlugins: ArrayBuffer[Plugin[VexRiscv]]) {
}

import VexInterfaceConfig._

object VexOnChipConfig {
  def default: VexOnChipConfig = default(false)

  def default(bigEndian: Boolean = false) = VexOnChipConfig(
    onChipRamSize = 32 kB,
    onChipRamHexFile = null,
    cpuPlugins = ArrayBuffer( //DebugPlugin added by the toplevel
      new IBusSimplePlugin(
        resetVector = resetVector,
        cmdForkOnSecondStage = true,
        cmdForkPersistence = true,
        prediction = NONE,
        catchAccessFault = false,
        compressedGen = false,
        bigEndian = bigEndian
      ),
      new DBusSimplePlugin(
        catchAddressMisaligned = false,
        catchAccessFault = false,
        earlyInjection = false,
        bigEndian = bigEndian
      ),
      new CsrPlugin(CsrPluginConfig.smallest(mtvecInit = 0x80000020l)),
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
      new YamlPlugin("cpu0.yaml")
    ),
    hardwareBreakpointCount = 2
  )

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

case class VexOnChip(config: VexOnChipConfig) extends Component {

  import config._

  val io = new Bundle {
    //Clocks / reset
    val reset = in Bool()
    val clk = in Bool()

    //Main components IO
    val jtag = slave(Jtag())

    // val iBus = master(Axi4ReadOnly(IBusSimpleBus.getAxi4Config()))
    // val dBus = master(Axi4Shared(DBusSimpleBus.getAxi4Config()))
    var iBus: Axi4ReadOnly = _
    var dBus: Axi4Shared = _
  }


  val resetCtrlClockDomain = ClockDomain(
    clock = io.clk,
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
    when(BufferCC(io.reset)) {
      systemClkResetCounter := 0
    }

    //Create all reset used later in the design
    val mainClkReset = RegNext(mainClkResetUnbuffered)
    val systemReset = RegNext(mainClkResetUnbuffered)
  }


  val systemClockDomain = ClockDomain(
    clock = io.clk,
    reset = resetCtrl.systemReset
  )

  val debugClockDomain = ClockDomain(
    clock = io.clk,
    reset = resetCtrl.mainClkReset
  )

  val core = new ClockingArea(systemClockDomain) {
    // Instantiate the CPU
    val cpu = new VexRiscv(
      config = VexRiscvConfig(
        plugins = cpuPlugins += new DebugPlugin(debugClockDomain, hardwareBreakpointCount)
      )
    )

    //Checkout plugins used to instanciate the CPU to connect them to the SoC
    val timerInterrupt = False
    val externalInterrupt = False
    var iBus: Axi4ReadOnly = _
    var dBus: Axi4Shared = _
    for (plugin <- cpu.plugins) plugin match {
      case plugin: IBusSimplePlugin => iBus = plugin.iBus.toAxi4ReadOnly().toFullConfig()
      case plugin: IBusCachedPlugin => iBus = plugin.iBus.toAxi4ReadOnly().toFullConfig()
      case plugin: DBusSimplePlugin => dBus = plugin.dBus.toAxi4Shared().toFullConfig()
      case plugin: DBusCachedPlugin => dBus = plugin.dBus.toAxi4Shared(stageCmd = true).toFullConfig()
      case plugin: CsrPlugin =>
        plugin.externalInterrupt := externalInterrupt
        plugin.timerInterrupt := timerInterrupt
      case plugin: DebugPlugin => plugin.debugClockDomain {
        resetCtrl.systemReset setWhen (RegNext(plugin.io.resetOut))
        io.jtag <> plugin.io.bus.fromJtag()
      }

      case _ =>
    }

    io.dBus = master(dBus.copy()).setName("dBus")

    val ram = Axi4SharedOnChipRam(
      dataWidth = 32,
      byteCount = onChipRamSize,
      idWidth = 4
    ).setName("onchip_name")


    val axiCrossbar = Axi4CrossbarFactory()

    axiCrossbar.addSlaves(
      ram.io.axi -> (0x80000000L, onChipRamSize),
      io.dBus -> (0x00000000L, BigInt(0x80000000L))
    )

    axiCrossbar.addConnections(
      dBus -> List(ram.io.axi, io.dBus)
    )

    axiCrossbar.addPipelining(ram.io.axi)((crossbar, ctrl) => {
      crossbar.sharedCmd.halfPipe() >> ctrl.sharedCmd
      crossbar.writeData >/-> ctrl.writeData
      crossbar.writeRsp << ctrl.writeRsp
      crossbar.readRsp << ctrl.readRsp
    })

    axiCrossbar.addPipelining(dBus)((cpu, crossbar) => {
      cpu.sharedCmd >> crossbar.sharedCmd
      cpu.writeData >> crossbar.writeData
      cpu.writeRsp << crossbar.writeRsp
      cpu.readRsp <-< crossbar.readRsp //Data cache directly use read responses without buffering, so pipeline it for FMax
    })

    axiCrossbar.build()

    io.iBus = master(iBus.copy()).setName("iBus")
    io.iBus <> iBus.toFullConfig()
  }
}

object GenVexOnChip {
  def main(args: Array[String]): Unit = {
    val name = if(args.size == 0) "VexCore" else args(0)
    SpinalVerilog(VexOnChip(VexOnChipConfig.default).setDefinitionName(name))
  }
}