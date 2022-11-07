package vexriscv.demo

import spinal.core._
import spinal.lib.bus.amba4.axi.{Axi4Config, Axi4CrossbarFactory, Axi4ReadOnly, Axi4Shared, Axi4SharedOnChipRam}
import spinal.lib.com.jtag.Jtag
import spinal.lib.eda.altera.{InterruptReceiverTag, ResetEmitterTag}
import spinal.lib.{master, slave}
import vexriscv.ip.InstructionCacheConfig
import vexriscv.plugin._
import vexriscv.{VexRiscv, VexRiscvConfig, plugin}

import scala.collection.mutable.ArrayBuffer
import scala.language.postfixOps

case class VexOnChipConfig
(iCacheSize: Int = 4 * 1024,
 dCacheSize: Int = 4096, // not used here but there
 onChipRamSize: BigInt = 32 KiB,
 onChipRamBinaryFile: String = null,
 hardwareBreakpointCount: Int = 2,
 replaceMemoryIP: Boolean = false,
 resetVector: BigInt = VexInterfaceConfig.resetVector,
 cpuPlugins: ArrayBuffer[Plugin[VexRiscv]] = VexOnChipConfig.defaultPlugins(bigEndian = false)) {
}
// object VexOnChipConfig {
//   def apply(iCacheSize: BigInt, onChipRamSize: BigInt,)
// }

import vexriscv.demo.VexInterfaceConfig._

object VexOnChipConfig {
  def defaultPlugins(bigEndian: Boolean) = ArrayBuffer( //DebugPlugin added by the toplevel
    new DBusSimplePlugin(
      catchAddressMisaligned = false,
      catchAccessFault = false,
      earlyInjection = false,
      bigEndian = bigEndian
    ),
    // new CsrPlugin(CsrPluginConfig.smallest(mtvecInit = 0x80000020l)),
    // new CsrPlugin(CsrPluginConfig.linuxFull(0x80000020L)),
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
    new StaticMemoryTranslatorPlugin(
      ioRange = addr => addr(31 downto 28) =/= 0x8 && addr(31 downto 16) =/= 0x0001
    ),
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
      catchAddressMisaligned = false,
      fenceiGenAsANop = true
    ),
    new YamlPlugin("cpu0.yaml"),
    new SimpleFormalPlugin
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

    val plugins = cpuPlugins += new DebugPlugin(ClockDomain.current.copy(reset = Bool().setName("debugReset")))

    // Instantiate the CPU
    val cpu = new VexRiscv(
      config = VexRiscvConfig(
        plugins = if (iCacheSize == 0) plugins += new IBusSimplePlugin(
          resetVector = resetVector,
          cmdForkOnSecondStage = true,
          cmdForkPersistence = true,
          prediction = DYNAMIC_TARGET,
          catchAccessFault = false,
          compressedGen = false,
          bigEndian = false
        ) else plugins += new IBusCachedPlugin(
          resetVector = resetVector,
          prediction = DYNAMIC_TARGET,
          config = InstructionCacheConfig(
            cacheSize = iCacheSize,
            bytePerLine = 32,
            wayCount = 1,
            addressWidth = 32,
            cpuDataWidth = 32,
            memDataWidth = 32,
            catchIllegalAccess = false,
            catchAccessFault = false,
            asyncTagMemory = false,
            twoCycleRam = true,
            twoCycleCache = true
          ),
          // askMemoryTranslation = true,
          // memoryTranslatorPortConfig = MemoryTranslatorPortConfig(
          //   portTlbSize = 4
          // )
        )
      )
    )

    cpu.rework {
      var iBus: Axi4ReadOnly = null
      var dBus: Axi4Shared = null
      // var dBus: Axi4 = null
      for (plugin <- cpu.plugins) plugin match {
        case plugin: IBusSimplePlugin =>
          plugin.iBus.setAsDirectionLess() //Unset IO properties of iBus
          iBus = plugin.iBus.toAxi4ReadOnly()
            .setName("iBus")
            .addTag(ClockDomainTag(ClockDomain.current)) //Specify a clock domain to the iBus (used by QSysify)
        case plugin: IBusCachedPlugin =>
          plugin.iBus.setAsDirectionLess() //Unset IO properties of iBus
          iBus = plugin.iBus.toAxi4ReadOnly()
            .setName("iBus")
            .addTag(ClockDomainTag(ClockDomain.current)) //Specify a clock domain to the iBus (used by QSysify)
        case plugin: DBusSimplePlugin =>
          plugin.dBus.setAsDirectionLess()
          dBus = plugin.dBus.toAxi4Shared()
            .addTag(ClockDomainTag(ClockDomain.current))
        case plugin: DBusCachedPlugin =>
          plugin.dBus.setAsDirectionLess()
          dBus = plugin.dBus.toAxi4Shared(stageCmd = true)
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
        case plugin: SimpleFormalPlugin => {
          println("Enabled SimpleFormalPlugin")
          master(plugin.rvfi).setName("rvfi")
        }
        case _ =>
      }

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
        ram.getAXIPort -> (0x80000000L, onChipRamSize),
        reqBus -> (0x00000000L, BigInt(0x80000000L))
      )

      axiCrossbar.addConnections(
        dBus -> List(ram.getAXIPort, reqBus),
        iBus -> List(ram.getAXIPort, reqBus)
      )

      axiCrossbar.addPipelining(ram.getAXIPort)((crossbar, ctrl) => {
        crossbar.sharedCmd.halfPipe() >> ctrl.sharedCmd
        crossbar.writeData >/-> ctrl.writeData
        crossbar.writeRsp << ctrl.writeRsp
        crossbar.readRsp << ctrl.readRsp
      })

      axiCrossbar.addPipelining(reqBus)((crossbar, ctrl) => {
        crossbar.sharedCmd.halfPipe() >> ctrl.sharedCmd
        crossbar.writeData.halfPipe() >> ctrl.writeData
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

      val reqBusFull = master(reqBus.toAxi4().toFullConfig().setName("dBus"))
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
    SpinalVerilog(VexOnChip(VexOnChipConfig.default.copy(iCacheSize = 16 * 1024, onChipRamSize = 52 kB))
      .setDefinitionName(name))
  }
}