//******************************************************************************
// Copyright (c) 2019 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// VexRiscv Tile Wrapper
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package vexriscv.chipyard

import chisel3._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.prci.ClockSinkParameters
import freechips.rocketchip.rocket._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._

case class VexRiscvSmpTileAttachParams
(tileParams: VexRiscvSmpTileParams,
 crossingParams: RocketCrossingParams
) extends CanAttachTile {
  type TileType = VexRiscvSmpTile
  val lookup = PriorityMuxHartIdFromSeq(Seq(tileParams))
}

case class VexRiscvCoreSmpParams
(bootFreqHz: BigInt = BigInt(1700000000))
  extends CoreParams {
  /* DO NOT CHANGE BELOW THIS */
  val useVM: Boolean = true
  val useHypervisor: Boolean = false
  val useUser: Boolean = true
  // here
  val useSupervisor: Boolean = true
  val useDebug: Boolean = false
  val useAtomics: Boolean = true
  val useAtomicsOnlyForIO: Boolean = false // copied from Rocket
  val useCompressed: Boolean = false
  override val useVector: Boolean = false
  val useSCIE: Boolean = false
  val useRVE: Boolean = false
  val mulDiv: Option[MulDivParams] = Some(MulDivParams()) // copied from Rocket
  val fpu: Option[FPUParams] = Some(FPUParams()) // copied fma latencies from Rocket
  val nLocalInterrupts: Int = 0
  val useNMI: Boolean = false
  val nPMPs: Int = 0 // TODO: Check
  val pmpGranularity: Int = 4 // copied from Rocket
  val nBreakpoints: Int = 0 // TODO: Check
  val useBPWatch: Boolean = false
  val mcontextWidth: Int = 0 // TODO: Check
  val scontextWidth: Int = 0 // TODO: Check
  val nPerfCounters: Int = 29
  val haveBasicCounters: Boolean = true
  val haveFSDirty: Boolean = false
  val misaWritable: Boolean = false
  val haveCFlush: Boolean = false
  val nL2TLBEntries: Int = 512 // copied from Rocket
  val nL2TLBWays: Int = 1
  val mtvecInit: Option[BigInt] = Some(BigInt(0)) // copied from Rocket
  val mtvecWritable: Boolean = true // copied from Rocket
  val instBits: Int = if (useCompressed) 16 else 32
  val lrscCycles: Int = 80 // copied from Rocket
  val decodeWidth: Int = 1 // TODO: Check
  val fetchWidth: Int = 1 // TODO: Check
  val retireWidth: Int = 1
  val nPTECacheEntries: Int = 8 // TODO: Check
}

// TODO: BTBParams, DCacheParams, ICacheParams are incorrect in DTB... figure out defaults in VexRiscv and put in DTB
case class VexRiscvSmpTileParams
(name: Option[String] = Some("vexRiscvSmp_tile"),
 hartId: Int = 0,
 core: VexRiscvCoreParams = VexRiscvCoreParams()
) extends InstantiableTileParams[VexRiscvSmpTile] {
  val beuAddr: Option[BigInt] = None
  val blockerCtrlAddr: Option[BigInt] = None
  val btb: Option[BTBParams] = Some(BTBParams())
  val boundaryBuffers: Boolean = false
  val dcache: Option[DCacheParams] = Some(DCacheParams())
  val icache: Option[ICacheParams] = Some(ICacheParams())
  val clockSinkParams: ClockSinkParameters = ClockSinkParameters()

  def instantiate(crossing: TileCrossingParamsLike, lookup: LookupByHartIdImpl)(implicit p: Parameters): VexRiscvSmpTile = {
    new VexRiscvSmpTile(this, crossing, lookup)
  }
}

// Use diplomatic interrupts to external interrupts from the subsystem into the tile
trait SinksExternalInterruptsSmp {
  this: BaseTile =>

  val intInwardNode = intXbar.intnode :=* IntIdentityNode()(ValName("int_local"))
  protected val intSinkNode = IntSinkNode(IntSinkPortSimple())
  intSinkNode := intXbar.intnode

  def cpuDevice: Device

  val intcDevice = new DeviceSnippet {
    override def parent = Some(cpuDevice)

    def describe(): Description = {
      Description("interrupt-controller", Map(
        "compatible" -> "riscv,cpu-intc".asProperty,
        "interrupt-controller" -> Nil,
        "#interrupt-cells" -> 1.asProperty))
    }
  }

  ResourceBinding {
    intSinkNode.edges.in.flatMap(_.source.sources).map { case s =>
      for (i <- s.range.start until s.range.end) {
        csrIntMap.lift(i).foreach { j =>
          s.resources.foreach { r =>
            r.bind(intcDevice, ResourceInt(j))
          }
        }
      }
    }
  }

  // TODO: the order of the following two functions must match, and
  //         also match the order which things are connected to the
  //         per-tile crossbar in subsystem.HasTiles.connectInterrupts

  // debug, msip, mtip, meip, seip, lip offsets in CSRs
  def csrIntMap: List[Int] = {
    val nlips = tileParams.core.nLocalInterrupts
    val seip = if (usingSupervisor) Seq(9) else Nil
    List(65535, 3, 7, 11) ++ seip ++ List.tabulate(nlips)(_ + 16)
  }

  // go from flat diplomatic Interrupts to bundled TileInterrupts
  def decodeCoreInterrupts(core: TileInterrupts): Unit = {
    val async_ips = Seq(core.debug)
    val periph_ips = Seq(
      core.msip,
      core.mtip,
      core.meip)

    val seip = if (core.seip.isDefined) Seq(core.seip.get) else Nil

    val core_ips = core.lip

    val (interrupts, _) = intSinkNode.in(0)
    (async_ips ++ periph_ips ++ seip ++ core_ips).zip(interrupts).foreach { case (c, i) => c := i }
  }
}


class VexRiscvSmpTile private
(val vexRiscvParams: VexRiscvSmpTileParams,
 crossing: ClockCrossingType,
 lookup: LookupByHartIdImpl,
 q: Parameters)
  extends BaseTile(vexRiscvParams, crossing, lookup, q)
    with SinksExternalInterruptsSmp
    with SourcesExternalNotifications {
  /**
   * Setup parameters:
   * Private constructor ensures altered LazyModule.p is used implicitly
   */
  def this(params: VexRiscvSmpTileParams, crossing: TileCrossingParamsLike, lookup: LookupByHartIdImpl)(implicit p: Parameters) =
    this(params, crossing.crossingType, lookup, p)

  val intOutwardNode = IntIdentityNode()
  val slaveNode = TLIdentityNode()
  val masterNode = visibilityNode

  tlOtherMastersNode := tlMasterXbar.node
  masterNode :=* tlOtherMastersNode
  DisableMonitors { implicit p => tlSlaveXbar.node :*= slaveNode }

  val cpuDevice: SimpleDevice = new SimpleDevice("cpu", Seq("openhwgroup,vexRiscv", "riscv")) {
    override def parent = Some(ResourceAnchors.cpus)

    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(name, mapping ++
        cpuProperties ++
        nextLevelCacheProperty ++
        tileProperties)
    }
  }

  ResourceBinding {
    Resource(cpuDevice, "reg").bind(ResourceAddress(staticIdForMetadataUseOnly))
  }

  override def makeMasterBoundaryBuffers(crossing: ClockCrossingType)(implicit p: Parameters) = crossing match {
    case _: RationalCrossing =>
      if (!vexRiscvParams.boundaryBuffers) TLBuffer(BufferParams.none)
      else TLBuffer(BufferParams.none, BufferParams.flow, BufferParams.none, BufferParams.flow, BufferParams(1))
    case _ => TLBuffer(BufferParams.none)
  }

  override def makeSlaveBoundaryBuffers(crossing: ClockCrossingType)(implicit p: Parameters) = crossing match {
    case _: RationalCrossing =>
      if (!vexRiscvParams.boundaryBuffers) TLBuffer(BufferParams.none)
      else TLBuffer(BufferParams.flow, BufferParams.none, BufferParams.none, BufferParams.none, BufferParams.none)
    case _ => TLBuffer(BufferParams.none)
  }

  override lazy val module = new VexRiscvSmpTileModuleImp(this)

  /**
   * Setup AXI4 memory interface.
   * THESE ARE CONSTANTS.
   */
  val portName = "vexRiscv-mem-port-axi4"
  val idBits = 4
  val beatBytes = masterPortBeatBytes
  val sourceBits = 1 // equiv. to userBits (i think)

  val memAXI4Node = AXI4MasterNode(Seq(AXI4MasterPortParameters(
    masters = Seq(AXI4MasterParameters(portName + "-dmem")))))

  val memoryTap = TLIdentityNode()

  (tlMasterXbar.node
    := memoryTap
    := TLBuffer()
    := TLFIFOFixer(TLFIFOFixer.all) // fix FIFO ordering
    := TLWidthWidget(beatBytes) // reduce size of TL
    := AXI4ToTL() // convert to TL
    := AXI4UserYanker(Some(2)) // remove user field on AXI interface. need but in reality user intf. not needed
    := AXI4Fragmenter() // deal with multi-beat xacts
    := memAXI4Node)

  def connectVexRiscvInterrupts(msip: Bool, mtip: Bool, meip: Bool): Unit = {
    val (interrupts, _) = intSinkNode.in.head
    // debug := interrupts(0)
    msip := interrupts(1)
    mtip := interrupts(2)
    meip := interrupts(3)
    // m_s_eip := Cat(interrupts(4), interrupts(3))
  }
}

class VexRiscvSmpTileModuleImp(outer: VexRiscvSmpTile) extends BaseTileModuleImp(outer) {
  // annotate the parameters
  Annotated.params(this, outer.vexRiscvParams)

  val debugBaseAddr = BigInt(0x0) // CONSTANT: based on default debug module
  val debugSz = BigInt(0x1000) // CONSTANT: based on default debug module

  // have the main memory, bootrom, debug regions be executable
  val bootromParams = p(BootROMLocated(InSubsystem)).get
  if (p(ExtMem).nonEmpty) {
    val tohostAddr = BigInt(0x80001000L) // CONSTANT: based on default sw (assume within extMem region)
    val fromhostAddr = BigInt(0x80001040L) // CONSTANT: based on default sw (assume within extMem region)

    val executeRegionBases = Seq(p(ExtMem).get.master.base, bootromParams.address, debugBaseAddr, BigInt(0x0), BigInt(0x0))
    val executeRegionSzs = Seq(p(ExtMem).get.master.size, BigInt(bootromParams.size), debugSz, BigInt(0x0), BigInt(0x0))
    val executeRegionCnt = executeRegionBases.length

    // have the main memory be cached, but don't cache tohost/fromhost addresses
    // TODO: current cache subsystem can only support 1 cacheable region... so cache AFTER the tohost/fromhost addresses
    val wordOffset = 0x40
    val (cacheableRegionBases, cacheableRegionSzs) = if (true /* outer.vexRiscvParams.core.enableToFromHostCaching */ ) {
      val bases = Seq(p(ExtMem).get.master.base, BigInt(0x0), BigInt(0x0), BigInt(0x0), BigInt(0x0))
      val sizes = Seq(p(ExtMem).get.master.size, BigInt(0x0), BigInt(0x0), BigInt(0x0), BigInt(0x0))
      (bases, sizes)
    } else {
      val bases = Seq(fromhostAddr + 0x40, p(ExtMem).get.master.base, BigInt(0x0), BigInt(0x0), BigInt(0x0))
      val sizes = Seq(p(ExtMem).get.master.size - (fromhostAddr + 0x40 - p(ExtMem).get.master.base), tohostAddr - p(ExtMem).get.master.base, BigInt(0x0), BigInt(0x0), BigInt(0x0))
      (bases, sizes)
    }
    val cacheableRegionCnt = cacheableRegionBases.length
  }

  // Add 2 to account for the extra clock and reset included with each
  // instruction in the original trace port implementation. These have since
  // been removed from TracedInstruction.
  val traceInstSz = (new freechips.rocketchip.rocket.TracedInstruction).getWidth + 2

  val core = Module(new VexCoreSmp(4))
  println(s"core: $core")

  core.io.clk := clock
  core.io.reset := reset.asBool

  // outer.connectVexRiscvInterrupts(core.io.softwareInterrupt, core.io.timerInterrupt, core.io.externalInterrupt)

  outer.traceSourceNode.bundle := DontCare
  outer.traceSourceNode.bundle foreach (t => t.valid := false.B)

  // connect the axi interface
  outer.memAXI4Node.out.head match {
    case (out, edgeOut) =>
      core.io.connectDMem(out)
  }
}
