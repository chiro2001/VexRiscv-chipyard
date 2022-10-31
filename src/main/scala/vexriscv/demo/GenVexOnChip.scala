package vexriscv.demo

import spinal.core._
import spinal.lib.bus.amba4.axi.{Axi4CrossbarFactory, Axi4ReadOnly, Axi4Shared, Axi4SharedOnChipRam}
import spinal.lib.com.jtag.Jtag
import spinal.lib.eda.altera.{InterruptReceiverTag, ResetEmitterTag}
import spinal.lib.{master, slave}
import vexriscv.plugin._
import vexriscv.{VexRiscv, VexRiscvConfig, plugin}

import scala.collection.mutable.ArrayBuffer
import scala.language.postfixOps

case class VexOnChipConfig
(iCacheSize: BigInt = 4 kB,
 onChipRamSize: BigInt = 32 kB,
 onChipRamHexFile: String = null,
 hardwareBreakpointCount: Int = 2,
 cpuPlugins: ArrayBuffer[Plugin[VexRiscv]] = VexOnChipConfig.defaultPlugins(bigEndian = false)) {
}
// object VexOnChipConfig {
//   def apply(iCacheSize: BigInt, onChipRamSize: BigInt,)
// }

import vexriscv.demo.VexInterfaceConfig._

object VexOnChipConfig {
  def defaultPlugins(bigEndian: Boolean) = ArrayBuffer( //DebugPlugin added by the toplevel
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
  )

  def default: VexOnChipConfig = default()

  def default(bigEndian: Boolean = false) = VexOnChipConfig(
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

object VexOnChip {
  def apply(config: VexOnChipConfig): VexRiscv = {
    import config._

    // Instantiate the CPU
    val cpu = new VexRiscv(
      config = VexRiscvConfig(
        plugins = cpuPlugins += new DebugPlugin(ClockDomain.current.copy(reset = Bool().setName("debugReset")))
      )
    )

    cpu.rework {
      var iBus: Axi4ReadOnly = null
      var dBus: Axi4Shared = null
      // var dBus: Axi4 = null
      for (plugin <- cpu.plugins) plugin match {
        case plugin: IBusSimplePlugin =>
          plugin.iBus.setAsDirectionLess() //Unset IO properties of iBus
          iBus = master(plugin.iBus.toAxi4ReadOnly().toFullConfig())
            .setName("iBus")
            .addTag(ClockDomainTag(ClockDomain.current)) //Specify a clock domain to the iBus (used by QSysify)
        case plugin: IBusCachedPlugin =>
          plugin.iBus.setAsDirectionLess() //Unset IO properties of iBus
          iBus = master(plugin.iBus.toAxi4ReadOnly().toFullConfig())
            .setName("iBus")
            .addTag(ClockDomainTag(ClockDomain.current)) //Specify a clock domain to the iBus (used by QSysify)
        case plugin: DBusSimplePlugin =>
          plugin.dBus.setAsDirectionLess()
          dBus = plugin.dBus.toAxi4Shared().toFullConfig()
            .addTag(ClockDomainTag(ClockDomain.current))
        case plugin: DBusCachedPlugin =>
          plugin.dBus.setAsDirectionLess()
          dBus = plugin.dBus.toAxi4Shared(stageCmd = true).toFullConfig()
            .addTag(ClockDomainTag(ClockDomain.current))
        case plugin: DebugPlugin => plugin.debugClockDomain {
          plugin.io.bus.setAsDirectionLess()
          val jtag = slave(new Jtag())
            .setName("jtag")
          jtag <> plugin.io.bus.fromJtag()
          plugin.io.resetOut
            .addTag(ResetEmitterTag(plugin.debugClockDomain))
            .parent = null //Avoid the io bundle to be interpreted as a QSys conduit
        }
        case _ =>
      }
      for (plugin <- cpu.plugins) plugin match {
        case plugin: CsrPlugin => {
          plugin.externalInterrupt
            .addTag(InterruptReceiverTag(iBus, ClockDomain.current))
          plugin.timerInterrupt
            .addTag(InterruptReceiverTag(iBus, ClockDomain.current))
        }
        case _ =>
      }

      val reqBus = dBus.copy()

      val ram = Axi4SharedOnChipRam(
        dataWidth = 32,
        byteCount = onChipRamSize,
        idWidth = 4
      ).setName("onchip_mem")


      val axiCrossbar = Axi4CrossbarFactory()

      axiCrossbar.addSlaves(
        ram.io.axi -> (0x80000000L, onChipRamSize),
        reqBus -> (0x00000000L, BigInt(0x80000000L))
      )

      axiCrossbar.addConnections(
        dBus -> List(ram.io.axi, reqBus)
      )

      axiCrossbar.addPipelining(ram.io.axi)((crossbar, ctrl) => {
        crossbar.sharedCmd.halfPipe() >> ctrl.sharedCmd
        crossbar.writeData >/-> ctrl.writeData
        crossbar.writeRsp << ctrl.writeRsp
        crossbar.readRsp << ctrl.readRsp
      })

      axiCrossbar.addPipelining(reqBus)((cpu, crossbar) => {
        cpu.sharedCmd >> crossbar.sharedCmd
        cpu.writeData >> crossbar.writeData
        cpu.writeRsp << crossbar.writeRsp
        cpu.readRsp <-< crossbar.readRsp //Data cache directly use read responses without buffering, so pipeline it for FMax
      })

      axiCrossbar.build()

      val reqBusFull = master(reqBus.toAxi4().setName("dBus"))
    }
    cpu
  }
}

object GenVexOnChip {
  def run(config: VexOnChipConfig, name: String = "VexCore"): Unit = {
    SpinalVerilog(VexOnChip(config).setDefinitionName(name))
  }

  def main(args: Array[String]): Unit = {
    val name = if (args.isEmpty) "VexCore" else args(0)
    SpinalVerilog(VexOnChip(VexOnChipConfig.default).setDefinitionName(name))
  }
}