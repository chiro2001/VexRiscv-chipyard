package vexriscv.demo

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4ReadOnly
import spinal.lib.com.jtag.Jtag
import spinal.lib.eda.altera.{InterruptReceiverTag, ResetEmitterTag}
// import vexriscv.demo.VexInterfaceConfig._
import vexriscv.ip.{DataCacheConfig, InstructionCacheConfig}
import vexriscv.plugin._
import vexriscv.{VexRiscv, VexRiscvConfig, plugin}

case class VexAXIJTAGConfig
(iCacheSize: Int = 4096,
 dCacheSize: Int = 4096,
 hardwareBreakpointCount: Int = 3,
 resetVector: BigInt = vexriscv.demo.VexInterfaceConfig.resetVector)

object VexAXIJTAGConfig {
  def default = VexAXIJTAGConfig()
}

object VexAXIJTAGCore {
  def run(config: VexAXIJTAGConfig): Unit = {
    import config._
    val report = SpinalVerilog {
      //CPU configuration
      val cpuConfig = VexRiscvConfig(
        plugins = List(
          new PcManagerSimplePlugin(resetVector, false),
          //          new IBusSimplePlugin(
          //            interfaceKeepData = false,
          //            catchAccessFault = false
          //          ),
          //          new DBusSimplePlugin(
          //            catchAddressMisaligned = false,
          //            catchAccessFault = false
          //          ),
          if (iCacheSize != 0) new IBusCachedPlugin(
            prediction = DYNAMIC_TARGET,
            config = InstructionCacheConfig(
              cacheSize = iCacheSize,
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
          ) else new IBusSimplePlugin(
            resetVector = resetVector,
            false, true
          ),
          if (dCacheSize != 0) new DBusCachedPlugin(
            config = new DataCacheConfig(
              cacheSize = dCacheSize,
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
          ) else new DBusSimplePlugin(
            catchAddressMisaligned = true,
            catchAccessFault = true
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
          new DebugPlugin(ClockDomain.current.copy(reset = Bool().setName("debugReset"))),
          new BranchPlugin(
            earlyBranch = false,
            catchAddressMisaligned = true
          ),
          new CsrPlugin(
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
          ),
          new YamlPlugin("cpu0.yaml")
        )
      )

      //CPU instanciation
      val cpu = new VexRiscv(cpuConfig)

      cpu.rework {
        var iBus: Axi4ReadOnly = null
        for (plugin <- cpuConfig.plugins) plugin match {
          case plugin: IBusSimplePlugin => {
            plugin.iBus.setAsDirectionLess() //Unset IO properties of iBus
            iBus = master(plugin.iBus.toAxi4ReadOnly().toFullConfig())
              .setName("iBus")
              .addTag(ClockDomainTag(ClockDomain.current)) //Specify a clock domain to the iBus (used by QSysify)
          }
          case plugin: IBusCachedPlugin => {
            plugin.iBus.setAsDirectionLess() //Unset IO properties of iBus
            iBus = master(plugin.iBus.toAxi4ReadOnly().toFullConfig())
              .setName("iBus")
              .addTag(ClockDomainTag(ClockDomain.current)) //Specify a clock domain to the iBus (used by QSysify)
          }
          case plugin: DBusSimplePlugin => {
            plugin.dBus.setAsDirectionLess()
            master(plugin.dBus.toAxi4Shared().toAxi4().toFullConfig())
              .setName("dBus")
              .addTag(ClockDomainTag(ClockDomain.current))
          }
          case plugin: DBusCachedPlugin => {
            plugin.dBus.setAsDirectionLess()
            master(plugin.dBus.toAxi4Shared().toAxi4().toFullConfig())
              .setName("dBus")
              .addTag(ClockDomainTag(ClockDomain.current))
          }
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
        for (plugin <- cpuConfig.plugins) plugin match {
          case plugin: CsrPlugin => {
            plugin.externalInterrupt
              .addTag(InterruptReceiverTag(iBus, ClockDomain.current))
            plugin.timerInterrupt
              .addTag(InterruptReceiverTag(iBus, ClockDomain.current))
          }
          case _ =>
        }
      }
      cpu.setDefinitionName("VexCore")
    }
  }
}

object GenAXIJTAG extends App {
  VexAXIJTAGCore.run(VexAXIJTAGConfig.default)
}
