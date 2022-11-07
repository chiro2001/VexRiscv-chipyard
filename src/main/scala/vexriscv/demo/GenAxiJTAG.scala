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

case class VexAxiJTAGConfig
(hartId: Int = 0,
 iCacheSize: Int = 4096,
 dCacheSize: Int = 4096,
 hardwareBreakpointCount: Int = 3,
 resetVector: BigInt = vexriscv.demo.VexInterfaceConfig.resetVector)

object VexAxiJTAGConfig {
  def default = VexAxiJTAGConfig()
}

object VexAxiJTAGCore {
  def run(config: VexAxiJTAGConfig, name: String = "VexCore"): Unit = {
    import config._
    println(s"GenAxiJTAG with config: ${config}")
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
            resetVector = resetVector,
            prediction = DYNAMIC_TARGET,
            historyRamSizeLog2 = 8,
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
              twoCycleRam = false,
              twoCycleCache = true),
            memoryTranslatorPortConfig = null
            // askMemoryTranslation = true,
            // memoryTranslatorPortConfig = MemoryTranslatorPortConfig(
            //   portTlbSize = 4
            // )
            // memoryTranslatorPortConfig = if (iCacheSize != 0) MmuPortConfig(
            //   portTlbSize = 4
            // ) else null
          ) else new IBusSimplePlugin(
            resetVector = resetVector,
            false, true,
            catchAccessFault = true
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
            memoryTranslatorPortConfig = null,
            // memoryTranslatorPortConfig = MemoryTranslatorPortConfig(
            //   portTlbSize = 6
            // )
            // memoryTranslatorPortConfig = if (dCacheSize != 0) MmuPortConfig(
            //   portTlbSize = 4
            // ) else null
          ) else new DBusSimplePlugin(
            catchAddressMisaligned = true,
            catchAccessFault = true
          ),
          // if (iCacheSize != 0 || dCacheSize != 0)
          //   new MmuPlugin(
          //     virtualRange = _ (31 downto 28) === 0xC,
          //     ioRange = _ (31 downto 28) === 0xF
          //   ) else
          new StaticMemoryTranslatorPlugin(
            // ioRange = _ (31 downto 24) === 0x54
            // for Uart and SPIFlash
            // ioRange = addr => addr(31 downto 24) === 0x54 || addr(31 downto 24) === 0x20
            // except ram
            ioRange = _ (31 downto 28) =/= 0x8
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
          // new MulPlugin,
          // new MulDivIterativePlugin(
          //   genMul = false,
          //   genDiv = true,
          //   mulUnrollFactor = 32,
          //   divUnrollFactor = 1
          // ),
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
              mhartid = hartId,
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
            // CsrPluginConfig.linuxMinimal(0x80000020L).copy(ebreakGen = false)
          ),
          new YamlPlugin("cpu0.yaml"),
          new SimpleFormalPlugin
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
          case plugin: SimpleFormalPlugin => {
            println("Enabled SimpleFormalPlugin")
            master(plugin.rvfi).setName("rvfi")
          }
          case _ =>
        }
      }
      cpu.setDefinitionName(name)
    }
  }
}

object GenAxiJTAG extends App {
  VexAxiJTAGCore.run(VexAxiJTAGConfig.default)
}
