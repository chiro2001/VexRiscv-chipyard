package vexriscv.demo

import spinal.core._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config, Axi4CrossbarFactory, Axi4ReadOnly, Axi4Shared}
import spinal.lib.com.jtag.Jtag
import spinal.lib.eda.altera.ResetEmitterTag
import spinal.lib.{BufferCC, master, slave}
import vexriscv.ip.{DataCacheConfig, InstructionCacheConfig}
import vexriscv.plugin._
import vexriscv.{VexRiscv, VexRiscvConfig, plugin}

import scala.collection.mutable.ArrayBuffer
import scala.language.postfixOps

case class VexChipSmpConfig
(cpuCount: Int = 0,
 name: String = "VexChipSmp",
 configs: Seq[VexAxiConfig] = Seq(),
 negativeReset: Boolean = false,
 coreFrequency: HertzNumber = 5 MHz,
 replaceMemoryIP: Boolean = false,
 onChipRamSize: BigInt = 32 KiB,
 onChipRamBinaryFile: String = null,
)

object VexChipSmpConfig {
  val defaultCpuCount = 2

  def default(n: Int = defaultCpuCount) = VexChipSmpConfig(cpuCount = n,
    configs = (0 until n).map(i => VexAxiConfig.default.copy(iCacheSize = 0, dCacheSize = 0, hartId = i)))
}

class VexChipSmp(config: VexChipSmpConfig) extends Module {

  import config._

  val io = new Bundle {
    val sys_clock = in Bool()
    val reset = in Bool()
    var reqBus: Axi4 = _
  }.setName("")

  case class CpuPorts(iBus: Axi4ReadOnly, dBus: Axi4Shared, externalInterrupt: Bool, timerInterrupt: Bool)

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
    val cpus = configs.zipWithIndex.map(con => {
      new Area {

        import con._1._

        val cpuId = con._2
        val plugins = new ArrayBuffer[Plugin[VexRiscv]]()
        plugins.append(
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
          // new SimpleFormalPlugin
        )
        if (debug) plugins += new DebugPlugin(ClockDomain.current.copy(reset = Bool().setName("debugReset")))
        //CPU configuration
        val cpuConfig = VexRiscvConfig(plugins = plugins)

        //CPU instanciation
        val cpu = new VexRiscv(cpuConfig)

        var iBus: Axi4ReadOnly = null
        var dBus: Axi4Shared = null
        val timerInterrupt = False
        val externalInterrupt = False
        // var rvfi: SimpleRvfiPort = _
        // import io._

        cpu.rework {
          // var iBus: Axi4ReadOnly = null
          // var dBus: Axi4Shared = null
          for (plugin <- cpuConfig.plugins) plugin match {
            case plugin: IBusSimplePlugin => {
              // plugin.iBus.setAsDirectionLess() //Unset IO properties of iBus
              iBus = plugin.iBus.toAxi4ReadOnly() //.toFullConfig()
              // .setName("iBus")
              // .addTag(ClockDomainTag(ClockDomain.current)) //Specify a clock domain to the iBus (used by QSysify)
            }
            case plugin: IBusCachedPlugin => {
              // plugin.iBus.setAsDirectionLess() //Unset IO properties of iBus
              iBus = plugin.iBus.toAxi4ReadOnly() //.toFullConfig()
              // .setName("iBus")
              // .addTag(ClockDomainTag(ClockDomain.current)) //Specify a clock domain to the iBus (used by QSysify)
            }
            case plugin: DBusSimplePlugin => {
              // plugin.dBus.setAsDirectionLess()
              dBus = plugin.dBus.toAxi4Shared()
              // .setName("dBus")
              // .addTag(ClockDomainTag(ClockDomain.current))
            }
            case plugin: DBusCachedPlugin => {
              // plugin.dBus.setAsDirectionLess()
              dBus = plugin.dBus.toAxi4Shared()
              // .setName("dBus")
              // .addTag(ClockDomainTag(ClockDomain.current))
            }
            // case plugin: DebugPlugin => plugin.debugClockDomain {
            //   plugin.io.bus.setAsDirectionLess()
            //   val jtag = slave(new Jtag())
            //     .setName("jtag")
            //   jtag <> plugin.io.bus.fromJtag()
            //   plugin.io.resetOut
            //     .addTag(ResetEmitterTag(plugin.debugClockDomain))
            //     .parent = null //Avoid the io bundle to be interpreted as a QSys conduit
            // }
            case _ =>
          }
          for (plugin <- cpuConfig.plugins) plugin match {
            case plugin: CsrPlugin => {
              // plugin.externalInterrupt
              //   .addTag(InterruptReceiverTag(iBus, ClockDomain.current))
              // plugin.timerInterrupt
              //   .addTag(InterruptReceiverTag(iBus, ClockDomain.current))
              // plugin.externalInterrupt := externalInterrupt
              // plugin.timerInterrupt := timerInterrupt

              // plugin.externalInterrupt := False
              // plugin.timerInterrupt := False
            }
            case _ =>
          }
        }
        require(cpuId == con._1.hartId)
        cpu.setDefinitionName(config.name + "_cpu" + cpuId)
        // CpuPorts(iBus, dBus, externalInterrupt, timerInterrupt)
      }
    })

    val reqBus = Axi4Shared(Axi4Config(
      addressWidth = 32,
      dataWidth = 32,
      idWidth = 4,
      useLock = false,
      useRegion = false,
      useCache = false,
      useProt = false,
      useQos = false
    ))

    val ram: Axi4SharedOnChipRamWithAXIPort = (if (!replaceMemoryIP)
      new Axi4SharedOnChipRamMem(
        dataWidth = 32,
        byteCount = onChipRamSize,
        idWidth = 4,
        onChipRamBinaryFile = onChipRamBinaryFile
      ) else Axi4SharedOnChipRamDRM(
      dataWidth = 32,
      byteCount = onChipRamSize,
      idWidth = 4,
      onChipRamBinaryFile = onChipRamBinaryFile
    )).setName("onchip_mem")

    val axiCrossbar = Axi4CrossbarFactory()

    axiCrossbar.addSlaves(
      ram.getAXIPort -> (configs.head.resetVector, onChipRamSize),
      reqBus -> (0x80000000L, BigInt(0x10000000L))
    )
    axiCrossbar.addConnections(cpus.flatMap(cpu => Seq(
      cpu.iBus -> List(ram.getAXIPort, reqBus),
      cpu.dBus -> List(ram.getAXIPort, reqBus)
    )): _*)

    axiCrossbar.addPipelining(ram.getAXIPort)((crossbar, ctrl) => {
      crossbar.sharedCmd.halfPipe() >> ctrl.sharedCmd
      // crossbar.writeData >/-> ctrl.writeData
      crossbar.writeData >> ctrl.writeData
      crossbar.writeRsp << ctrl.writeRsp
      crossbar.readRsp << ctrl.readRsp
    })

    axiCrossbar.addPipelining(reqBus)((crossbar, ctrl) => {
      crossbar.sharedCmd.halfPipe() >> ctrl.sharedCmd
      crossbar.writeData.halfPipe() >> ctrl.writeData
      crossbar.writeRsp << ctrl.writeRsp
      crossbar.readRsp << ctrl.readRsp
    })

    axiCrossbar.build()

    io.reqBus = master(reqBus.toAxi4().toFullConfig().setName("reqBus"))
    cpus.foreach(cpu => {
      // cpu.timerInterrupt := False
      // cpu.externalInterrupt := False
    })
  }
}

object VexChipSmpCore {
  def run(config: VexChipSmpConfig): Unit = {
    SpinalConfig().generateVerilog(new VexChipSmp(config))
  }
}

object GenVexChipSmp extends App {
  VexChipSmpCore.run(VexChipSmpConfig.default(1))
}
