/**
*    Copyright 2011, Big Switch Networks, Inc.
*    Originally created by David Erickson, Stanford University
*
*    Licensed under the Apache License, Version 2.0 (the "License"); you may
*    not use this file except in compliance with the License. You may obtain
*    a copy of the License at
*
*         http://www.apache.org/licenses/LICENSE-2.0
*
*    Unless required by applicable law or agreed to in writing, software
*    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
*    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
*    License for the specific language governing permissions and limitations
*    under the License.
**/

package net.floodlightcontroller.linkdiscovery.internal;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IInfoProvider;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.util.SingletonTask;
import net.floodlightcontroller.linkdiscovery.ILinkDiscovery;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.linkdiscovery.LinkInfo;
import net.floodlightcontroller.linkdiscovery.LinkTuple;
import net.floodlightcontroller.linkdiscovery.SwitchPortTuple;
import net.floodlightcontroller.packet.BPDU;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.LLDP;
import net.floodlightcontroller.packet.LLDPTLV;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.storage.IResultSet;
import net.floodlightcontroller.storage.IStorageSourceService;
import net.floodlightcontroller.storage.IStorageSourceListener;
import net.floodlightcontroller.storage.OperatorPredicate;
import net.floodlightcontroller.storage.StorageException;
import net.floodlightcontroller.topology.ITopologyListener;
import net.floodlightcontroller.topology.web.TopologyWebRoutable;
import net.floodlightcontroller.util.EventHistory;
import net.floodlightcontroller.util.EventHistory.EvAction;

import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFPacketOut;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPhysicalPort.OFPortConfig;
import org.openflow.protocol.OFPhysicalPort.OFPortState;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFPortStatus;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.openflow.protocol.OFType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.util.HexString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class sends out LLDP messages containing the sending switch's datapath
 * id as well as the outgoing port number.  Received LLrescDP messages that
 * match a known switch cause a new LinkTuple to be created according to the
 * invariant rules listed below.  This new LinkTuple is also passed to routing
 * if it exists to trigger updates.
 *
 * This class also handles removing links that are associated to switch ports
 * that go down, and switches that are disconnected.
 *
 * Invariants:
 *  -portLinks and switchLinks will not contain empty Sets outside of
 *   critical sections
 *  -portLinks contains LinkTuples where one of the src or dst
 *   SwitchPortTuple matches the map key
 *  -switchLinks contains LinkTuples where one of the src or dst
 *   SwitchPortTuple's id matches the switch id
 *  -Each LinkTuple will be indexed into switchLinks for both
 *   src.id and dst.id, and portLinks for each src and dst
 *  -The updates queue is only added to from within a held write lock
 *
 * @author David Erickson (daviderickson@cs.stanford.edu)
 */
public class LinkDiscoveryManager
        implements IOFMessageListener, IOFSwitchListener, 
                   IStorageSourceListener, ILinkDiscoveryService,
                   IFloodlightModule, IInfoProvider {
    protected static Logger log = LoggerFactory.getLogger(LinkDiscoveryManager.class);

    // Names of table/fields for links in the storage API
    private static final String LINK_TABLE_NAME = "controller_link";
    private static final String LINK_ID = "id";
    private static final String LINK_SRC_SWITCH = "src_switch_id";
    private static final String LINK_SRC_PORT = "src_port";
    private static final String LINK_SRC_PORT_STATE = "src_port_state";
    private static final String LINK_DST_SWITCH = "dst_switch_id";
    private static final String LINK_DST_PORT = "dst_port";
    private static final String LINK_DST_PORT_STATE = "dst_port_state";
    private static final String LINK_VALID_TIME = "valid_time";

    private static final String SWITCH_TABLE_NAME = "controller_switch";
    private static final String SWITCH_CORE_SWITCH = "core_switch";

    protected IFloodlightProviderService floodlightProvider;
    protected IStorageSourceService storageSource;
    protected IRoutingService routingEngine;
    protected IRestApiService restApi;
    
    private static final String LLDP_STANDARD_DST_MAC_STRING = "01:80:c2:00:00:00";
    // BigSwitch OUI is 5C:16:C7, so 5D:16:C7 is the multicast version
    private static final String LLDP_BSN_DST_MAC_STRING = "5d:16:c7:00:00:01";

    /**
     * Map from link to the most recent time it was verified functioning
     */
    protected Map<LinkTuple, LinkInfo> links;
    protected int lldpFrequency = 15 * 1000; // sending frequency
    protected int lldpTimeout = 35 * 1000; // timeout
    LLDPTLV controllerTLV;
    protected ReentrantReadWriteLock lock;

    /**
     * Map from a id:port to the set of links containing it as an endpoint
     */
    protected Map<SwitchPortTuple, Set<LinkTuple>> portLinks;

    /**
     * Set of link tuples over which multicast LLDPs are received
     * and unicast LLDPs are not received.
     */
    protected Map<SwitchPortTuple, Set<LinkTuple>> portBroadcastDomainLinks;

    SingletonTask loopDetectTask;

    protected volatile boolean shuttingDown = false;

    /**
     * Map from switch id to a set of all links with it as an endpoint
     */
    protected Map<IOFSwitch, Set<LinkTuple>> switchLinks;
    /* topology aware components are called in the order they were added to the
     * the array */
    protected ArrayList<ILinkDiscoveryListener> linkDiscoveryAware;
    protected ArrayList<ITopologyListener> topologyAware;
    protected BlockingQueue<Update> updates;
    protected Thread updatesThread;

    //This map provides the ids of broadcast domains connected to a switch cluster
    protected Map<Long, Set<Long>> switchClusterBroadcastDomainMap;

    protected boolean isTopologyValid = false;

    public static enum UpdateOperation {ADD, UPDATE, REMOVE,
                                        SWITCH_UPDATED, TOPOLOGY_CHANGED};

    public int getLldpFrequency() {
        return lldpFrequency;
    }

    public int getLldpTimeout() {
        return lldpTimeout;
    }

    public Map<SwitchPortTuple, Set<LinkTuple>> getPortLinks() {
        return portLinks;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    protected class Update {
        public long src;
        public short srcPort;
        public int srcPortState;
        public long dst;
        public short dstPort;
        public int dstPortState;
        public ILinkDiscovery.LinkType type;
        public UpdateOperation operation;

        public Update(long src, short srcPort, int srcPortState,
                      long dst, short dstPort, int dstPortState,
                      ILinkDiscovery.LinkType type,
                      UpdateOperation operation) {
            this.src = src;
            this.srcPort = srcPort;
            this.srcPortState = srcPortState;
            this.dst = dst;
            this.dstPort = dstPort;
            this.dstPortState = dstPortState;
            this.type = type;
            this.operation = operation;
        }

        public Update(LinkTuple lt, int srcPortState,
                      int dstPortState, ILinkDiscovery.LinkType type, UpdateOperation operation) {
            this(lt.getSrc().getSw().getId(), lt.getSrc().getPort(),
                 srcPortState, lt.getDst().getSw().getId(), lt.getDst().getPort(),
                 dstPortState, type, operation);
        }

        // For updtedSwitch(sw)
        public Update(IOFSwitch sw) {
            this.operation = UpdateOperation.SWITCH_UPDATED;
            this.src = sw.getId();
        }

        // Should only be used for CLUSTER_MERGED operations
        public Update(UpdateOperation operation) {
            this.operation = operation;
        }
    }
    
    private void doUpdatesThread() throws InterruptedException {
        do {
            Update update = updates.take();

            if (update.operation == UpdateOperation.TOPOLOGY_CHANGED) {
                if (topologyAware != null) {
                    for (ITopologyListener ta : topologyAware) { // order maintained
                        ta.toplogyChanged();
                    }
                } 
            }else {
                if (topologyAware != null) {
                    for (ILinkDiscoveryListener lda : linkDiscoveryAware) { // order maintained
                        if (log.isDebugEnabled()) {
                            log.debug("Dispatching topology update {} {} {} {} {}",
                                      new Object[]{update.operation,
                                                   update.src, update.srcPort,
                                                   update.dst, update.dstPort});
                        }
                        switch (update.operation) {
                            case ADD:
                                lda.addedLink(update.src, update.srcPort,
                                              update.srcPortState,
                                              update.dst, update.dstPort,
                                              update.dstPortState,
                                              update.type);
                                break;
                            case UPDATE:
                                lda.updatedLink(update.src, update.srcPort,
                                                update.srcPortState,
                                                update.dst, update.dstPort,
                                                update.dstPortState,
                                                update.type);
                                break;
                            case REMOVE:
                                lda.removedLink(update.src, update.srcPort,
                                                update.dst, update.dstPort);
                                break;
                            case SWITCH_UPDATED:
                                lda.updatedSwitch(update.src);
                                break;
                        }
                    }
                }
            }
        } while (updates.peek() != null);
    }

    protected void sendLLDPs(IOFSwitch sw, OFPhysicalPort port, String dstMacAddress) {

        if (log.isTraceEnabled()) {
            log.trace("Sending LLDP packet out of swich: {}, port: {}",
                    sw.getStringId(), port.getPortNumber());
        }

        Ethernet ethernet = new Ethernet()
            .setSourceMACAddress(port.getHardwareAddress())
            .setDestinationMACAddress(dstMacAddress)
            .setEtherType(Ethernet.TYPE_LLDP);
        // using "nearest customer bridge" MAC address for broadest possible propagation
        // through provider and TPMR bridges (see IEEE 802.1AB-2009 and 802.1Q-2011),
        // in particular the Linux bridge which behaves mostly like a provider bridge

        LLDP lldp = new LLDP();
        ethernet.setPayload(lldp);
        byte[] chassisId = new byte[] {4, 0, 0, 0, 0, 0, 0}; // filled in later
        byte[] portId = new byte[] {2, 0, 0}; // filled in later
        lldp.setChassisId(new LLDPTLV().setType((byte) 1).setLength((short) 7).setValue(chassisId));
        lldp.setPortId(new LLDPTLV().setType((byte) 2).setLength((short) 3).setValue(portId));
        lldp.setTtl(new LLDPTLV().setType((byte) 3).setLength((short) 2).setValue(new byte[] {0, 0x78}));

        // OpenFlow OUI - 00-26-E1
        byte[] dpidTLVValue = new byte[] {0x0, 0x26, (byte) 0xe1, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        LLDPTLV dpidTLV = new LLDPTLV().setType((byte) 127).setLength((short) 12).setValue(dpidTLVValue);
        lldp.getOptionalTLVList().add(dpidTLV);

        // Add the controller identifier to the TLV value.
        lldp.getOptionalTLVList().add(controllerTLV);

        byte[] dpidArray = new byte[8];
        ByteBuffer dpidBB = ByteBuffer.wrap(dpidArray);
        ByteBuffer portBB = ByteBuffer.wrap(portId, 1, 2);


        Long dpid = sw.getId();
        dpidBB.putLong(dpid);

        // set the ethernet source mac to last 6 bytes of dpid
        System.arraycopy(dpidArray, 2, ethernet.getSourceMACAddress(), 0, 6);
        // set the chassis id's value to last 6 bytes of dpid
        System.arraycopy(dpidArray, 2, chassisId, 1, 6);
        // set the optional tlv to the full dpid
        System.arraycopy(dpidArray, 0, dpidTLVValue, 4, 8);

        if (port.getPortNumber() == OFPort.OFPP_LOCAL.getValue())
            return;

        // set the portId to the outgoing port
        portBB.putShort(port.getPortNumber());
        log.trace("Sending LLDP out of interface: {}/{}",
                                sw.toString(), port.toString());

        // serialize and wrap in a packet out
        byte[] data = ethernet.serialize();
        OFPacketOut po = (OFPacketOut) floodlightProvider.getOFMessageFactory().getMessage(OFType.PACKET_OUT);
        po.setBufferId(OFPacketOut.BUFFER_ID_NONE);
        po.setInPort(OFPort.OFPP_NONE);

        // set actions
        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(port.getPortNumber(), (short) 0));
        po.setActions(actions);
        po.setActionsLength((short) OFActionOutput.MINIMUM_LENGTH);

        // set data
        po.setLengthU(OFPacketOut.MINIMUM_LENGTH + po.getActionsLength() + data.length);
        po.setPacketData(data);

        // send
        try {
            sw.write(po, null);
        } catch (IOException e) {
            log.error("Failure sending LLDP out port {} on switch {}",
                    new Object[]{ port.getPortNumber(), sw }, e);
        }

    }

    protected void sendLLDPs(SwitchPortTuple swt) {
        IOFSwitch sw = swt.getSw();

        if (sw == null) return;

        OFPhysicalPort port = sw.getPort(swt.getPort());
        if (port != null) {
            sendLLDPs(sw, port, LLDP_STANDARD_DST_MAC_STRING);
            sendLLDPs(sw, port, LLDP_BSN_DST_MAC_STRING);
        }
    }

    protected void sendLLDPs(IOFSwitch sw) {
        if (sw.getEnabledPorts() != null) {
            for (OFPhysicalPort port : sw.getEnabledPorts()) {
                sendLLDPs(sw, port, LLDP_STANDARD_DST_MAC_STRING);
                sendLLDPs(sw, port, LLDP_BSN_DST_MAC_STRING);
            }
        }
        sw.flush();
    }

    protected void sendLLDPs() {
        log.trace("Sending LLDP packets out of all the enabled ports");

        Map<Long, IOFSwitch> switches = floodlightProvider.getSwitches();
        for (Entry<Long, IOFSwitch> entry : switches.entrySet()) {
            IOFSwitch sw = entry.getValue();
            sendLLDPs(sw);
        }
    }

    protected void setControllerTLV() {
        //Setting the controllerTLVValue based on current nano time,
        //controller's IP address, and the network interface object hash
        //the corresponding IP address.

        final int prime = 7867;
        InetAddress localIPAddress = null;
        NetworkInterface localInterface = null;

        byte[] controllerTLVValue = new byte[] {0, 0, 0, 0, 0, 0, 0, 0};  // 8 byte value.
        ByteBuffer bb = ByteBuffer.allocate(10);

        try{
            localIPAddress = java.net.InetAddress.getLocalHost();
            localInterface = NetworkInterface.getByInetAddress(localIPAddress);
        } catch (Exception e) {
            e.printStackTrace();
        }

        Long result = System.nanoTime();
        if (localIPAddress != null)
            result = result * prime + IPv4.toIPv4Address(localIPAddress.getHostAddress());
        if (localInterface != null)
            result = result * prime + localInterface.hashCode();
        bb.putLong(result);


        bb.rewind();
        bb.get(controllerTLVValue, 0, 8);

        this.controllerTLV = new LLDPTLV().setType((byte) 0x0c).setLength((short) 8).setValue(controllerTLVValue);
    }

    @Override
    public String getName() {
        return "topology";
    }
 
    @Override
    public int getId() {
        return FlListenerID.TOPOLOGYIMPL;
    }

    @Override
    public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
        switch (msg.getType()) {
            case PACKET_IN:
                return this.handlePacketIn(sw, (OFPacketIn) msg, cntx);
            case PORT_STATUS:
                return this.handlePortStatus(sw, (OFPortStatus) msg);
        }

        log.error("Received an unexpected message {} from switch {}", msg, sw);
        return Command.CONTINUE;
    }

    private Command handleLldp(LLDP lldp, IOFSwitch sw, OFPacketIn pi, boolean isStandard, FloodlightContext cntx) {
        // If this is a malformed LLDP, or not from us, exit
        if (lldp.getPortId() == null || lldp.getPortId().getLength() != 3)
            return Command.CONTINUE;

        ByteBuffer portBB = ByteBuffer.wrap(lldp.getPortId().getValue());
        portBB.position(1);
        Short remotePort = portBB.getShort();
        IOFSwitch remoteSwitch = null;
        boolean myLLDP = false;

        // Verify this LLDP packet matches what we're looking for
        for (LLDPTLV lldptlv : lldp.getOptionalTLVList()) {
            if (lldptlv.getType() == 127 && lldptlv.getLength() == 12 &&
                    lldptlv.getValue()[0] == 0x0 && lldptlv.getValue()[1] == 0x26 &&
                    lldptlv.getValue()[2] == (byte)0xe1 && lldptlv.getValue()[3] == 0x0) {
                ByteBuffer dpidBB = ByteBuffer.wrap(lldptlv.getValue());
                remoteSwitch = floodlightProvider.getSwitches().get(dpidBB.getLong(4));
            }
            if (lldptlv.getType() == 12 && lldptlv.getLength() == 8 &&
                    lldptlv.getValue()[0] == controllerTLV.getValue()[0] &&
                    lldptlv.getValue()[1] == controllerTLV.getValue()[1] &&
                    lldptlv.getValue()[2] == controllerTLV.getValue()[2] &&
                    lldptlv.getValue()[3] == controllerTLV.getValue()[3] &&
                    lldptlv.getValue()[4] == controllerTLV.getValue()[4] &&
                    lldptlv.getValue()[5] == controllerTLV.getValue()[5] &&
                    lldptlv.getValue()[6] == controllerTLV.getValue()[6] &&
                    lldptlv.getValue()[7] == controllerTLV.getValue()[7]){
                ByteBuffer.wrap(lldptlv.getValue());
                myLLDP = true;
            }
        }

        if (myLLDP == false) {
            // This is not the LLDP sent by this controller.
            // If the LLDP message has multicast bit set, then we need to broadcast
            // the packet as a regular packet.
            Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
            if (eth.isMulticast()) {
                if (log.isTraceEnabled())
                    log.trace("Received a multicast LLDP packet from a different controller, allowing the packet to follow normal processing chain.");
                return Command.STOP;
            }
            if (log.isTraceEnabled()) {
                log.trace("Received a unicast LLDP packet from a different controller, stop processing the packet here.");
            }
            return Command.STOP;
        }

        if (remoteSwitch == null) {
            // Ignore LLDPs not generated by Floodlight, or from a switch that has recently
            // disconnected, or from a switch connected to another Floodlight instance
            if (log.isDebugEnabled()) {
                log.debug("Received LLDP from remote switch not connected to the controller");
            }
            return Command.STOP;
        }

        if (!remoteSwitch.portEnabled(remotePort)) {
            log.info("Ignoring link with disabled source port: switch {} port {}", remoteSwitch, remotePort);
            return Command.STOP;
        }
        if (!sw.portEnabled(pi.getInPort())) {
            log.info("Ignoring link with disabled dest port: switch {} port {}", sw, pi.getInPort());
            return Command.STOP;
        }

        OFPhysicalPort physicalPort = remoteSwitch.getPort(remotePort);
        int srcPortState = (physicalPort != null) ? physicalPort.getState() : 0;
        physicalPort = sw.getPort(remotePort);
        int dstPortState = (physicalPort != null) ? physicalPort.getState() : 0;

        // Store the time of update to this link, and push it out to routingEngine
        LinkTuple lt = new LinkTuple(new SwitchPortTuple(remoteSwitch, remotePort),
                new SwitchPortTuple(sw, pi.getInPort()));


        Integer srcPortStateObj = Integer.valueOf(srcPortState);
        Integer dstPortStateObj = Integer.valueOf(dstPortState);
        Long unicastValidTime = null;
        Long multicastValidTime = null;

        if (isStandard)
            unicastValidTime = System.currentTimeMillis();
        else
            multicastValidTime = System.currentTimeMillis();

        LinkInfo newLinkInfo =
                new LinkInfo(unicastValidTime, multicastValidTime, srcPortStateObj, dstPortStateObj);

        addOrUpdateLink(lt, newLinkInfo);

        // Consume this message
        return Command.STOP;
    }

    private Command handleBpdu(BPDU bpdu, IOFSwitch sw, OFPacketIn pi) {
        // TODO - handle STP here
        return Command.STOP;
    }

    protected Command handlePacketIn(IOFSwitch sw, OFPacketIn pi,
                                     FloodlightContext cntx) {
        Ethernet eth = 
            IFloodlightProviderService.bcStore.get(cntx, 
                                        IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

        if (eth.getPayload() instanceof LLDP)  {
            String dstMacString = HexString.toHexString(eth.getDestinationMACAddress());
            if (dstMacString.equals(LLDP_STANDARD_DST_MAC_STRING)) {
                return handleLldp((LLDP) eth.getPayload(), sw, pi, true, cntx);
            } else {
                return handleLldp((LLDP) eth.getPayload(), sw, pi, false, cntx);
            }
        }

        if (eth.getPayload() instanceof BPDU)
            return handleBpdu((BPDU) eth.getPayload(), sw, pi);

        return Command.CONTINUE;
    }

    protected ILinkDiscovery.LinkType getLinkType(LinkTuple lt, LinkInfo info) {
        if (info.getUnicastValidTime() != null) {
            return ILinkDiscovery.LinkType.DIRECT_LINK;
        } else if (info.getMulticastValidTime()  != null) {
            return ILinkDiscovery.LinkType.MULTIHOP_LINK;
        }
        return ILinkDiscovery.LinkType.INVALID_LINK;
    }
    
    protected void addOrUpdateLink(LinkTuple lt, LinkInfo newLinkInfo) {
        lock.writeLock().lock();
        try {
            LinkInfo oldLinkInfo = links.put(lt, newLinkInfo);

            UpdateOperation updateOperation = null;
            boolean linkChanged = false;

            if (oldLinkInfo == null) {
                // index it by switch source
                if (!switchLinks.containsKey(lt.getSrc().getSw()))
                    switchLinks.put(lt.getSrc().getSw(),
                                                    new HashSet<LinkTuple>());
                switchLinks.get(lt.getSrc().getSw()).add(lt);

                // index it by switch dest
                if (!switchLinks.containsKey(lt.getDst().getSw()))
                    switchLinks.put(lt.getDst().getSw(),
                                                    new HashSet<LinkTuple>());
                switchLinks.get(lt.getDst().getSw()).add(lt);

                // index both ends by switch:port
                if (!portLinks.containsKey(lt.getSrc()))
                    portLinks.put(lt.getSrc(), new HashSet<LinkTuple>());
                portLinks.get(lt.getSrc()).add(lt);

                if (!portLinks.containsKey(lt.getDst()))
                    portLinks.put(lt.getDst(), new HashSet<LinkTuple>());
                portLinks.get(lt.getDst()).add(lt);

                // Add to portNOFLinks if the unicast valid time is null
                if (newLinkInfo.getUnicastValidTime() == null)
                    addLinkToBroadcastDomain(lt);

                writeLink(lt, newLinkInfo);
                updateOperation = UpdateOperation.ADD;
                linkChanged = true;

                log.info("Added link {}", lt);
                // Add to event history
                evHistTopoLink(lt.getSrc().getSw().getId(),
                                lt.getDst().getSw().getId(),
                                lt.getSrc().getPort(),
                                lt.getDst().getPort(),
                                newLinkInfo.getSrcPortState(), newLinkInfo.getDstPortState(),
                                EvAction.LINK_ADDED, "LLDP Recvd");
            } else {
                // Since the link info is already there, we need to
                // update the right fields.
                if (newLinkInfo.getUnicastValidTime() == null) {
                    // This is due to a multicast LLDP, so copy the old unicast
                    // value.
                    if (oldLinkInfo.getUnicastValidTime() != null) {
                        newLinkInfo.setUnicastValidTime(oldLinkInfo.getUnicastValidTime());
                    }
                } else if (newLinkInfo.getMulticastValidTime() == null) {
                    // This is due to a unicast LLDP, so copy the old multicast
                    // value.
                    if (oldLinkInfo.getMulticastValidTime() != null) {
                        newLinkInfo.setMulticastValidTime(oldLinkInfo.getMulticastValidTime());
                    }
                }

                Long oldTime = oldLinkInfo.getUnicastValidTime();
                Long newTime = newLinkInfo.getUnicastValidTime();
                // the link has changed its state between openflow and non-openflow
                // if the unicastValidTimes are null or not null
                if (oldTime != null & newTime == null) {
                    // openflow -> non-openflow transition
                    // we need to add the link tuple to the portNOFLinks
                    addLinkToBroadcastDomain(lt);
                    linkChanged = true;
                } else if (oldTime == null & newTime != null) {
                    // non-openflow -> openflow transition
                    // we need to remove the link from the portNOFLinks
                    removeLinkFromBroadcastDomain(lt);
                    linkChanged = true;
                }

                // Only update the port states if they've changed
                if (newLinkInfo.getSrcPortState().intValue() != oldLinkInfo.getSrcPortState().intValue() ||
                        newLinkInfo.getDstPortState().intValue() != oldLinkInfo.getDstPortState().intValue())
                    linkChanged = true;

                // Write changes to storage. This will always write the updated
                // valid time, plus the port states if they've changed (i.e. if
                // they weren't set to null in the previous block of code.
                writeLink(lt, newLinkInfo);

                if (linkChanged) {
                    updateOperation = UpdateOperation.UPDATE;
                    if (log.isTraceEnabled()) {
                        log.trace("Updated link {}", lt);
                    }
                    log.info("Updated link {}", lt);
                    // Add to event history
                    evHistTopoLink(lt.getSrc().getSw().getId(),
                                    lt.getDst().getSw().getId(),
                                    lt.getSrc().getPort(),
                                    lt.getDst().getPort(),
                                    newLinkInfo.getSrcPortState(), newLinkInfo.getDstPortState(),
                                    EvAction.LINK_PORT_STATE_UPDATED,
                                    "LLDP Recvd");
                }
            }

            if (linkChanged) {
                updates.add(new Update(lt, newLinkInfo.getSrcPortState(), newLinkInfo.getDstPortState(), getLinkType(lt, newLinkInfo), updateOperation));
                //updateClusters();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Map<IOFSwitch, Set<LinkTuple>> getSwitchLinks() {
        return this.switchLinks;
    }

    /**
     * Removes links from memory and storage.
     * @param links The List of @LinkTuple to delete.
     */
    protected void deleteLinks(List<LinkTuple> links, String reason) {
        lock.writeLock().lock();
        try {
            for (LinkTuple lt : links) {
                this.switchLinks.get(lt.getSrc().getSw()).remove(lt);
                this.switchLinks.get(lt.getDst().getSw()).remove(lt);
                if (this.switchLinks.containsKey(lt.getSrc().getSw()) &&
                        this.switchLinks.get(lt.getSrc().getSw()).isEmpty())
                    this.switchLinks.remove(lt.getSrc().getSw());
                if (this.switchLinks.containsKey(lt.getDst().getSw()) &&
                        this.switchLinks.get(lt.getDst().getSw()).isEmpty())
                    this.switchLinks.remove(lt.getDst().getSw());

                this.portLinks.get(lt.getSrc()).remove(lt);
                if (this.portLinks.get(lt.getSrc()).isEmpty())
                    this.portLinks.remove(lt.getSrc());
                this.portLinks.get(lt.getDst()).remove(lt);
                if (this.portLinks.get(lt.getDst()).isEmpty())
                    this.portLinks.remove(lt.getDst());

                removeLinkFromBroadcastDomain(lt);

                this.links.remove(lt);
                updates.add(new Update(lt, 0, 0, null, UpdateOperation.REMOVE));

                // Update Event History
                evHistTopoLink(lt.getSrc().getSw().getId(),
                        lt.getDst().getSw().getId(),
                        lt.getSrc().getPort(),
                        lt.getDst().getPort(),
                        0, 0, // Port states
                        EvAction.LINK_DELETED, reason);
                removeLink(lt);


                if (log.isTraceEnabled()) {
                    log.trace("Deleted link {}", lt);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Handles an OFPortStatus message from a switch. We will add or
     * delete LinkTupes as well re-compute the topology if needed.
     * @param sw The IOFSwitch that sent the port status message
     * @param ps The OFPortStatus message
     * @return The Command to continue or stop after we process this message
     */
    protected Command handlePortStatus(IOFSwitch sw, OFPortStatus ps) {
        if (log.isDebugEnabled()) {
            log.debug("handlePortStatus: Switch {} port #{} reason {}; " +
                      "config is {} state is {}",
                      new Object[] {sw.getStringId(),
                                    ps.getDesc().getPortNumber(),
                                    ps.getReason(),
                                    ps.getDesc().getConfig(),
                                    ps.getDesc().getState()});
        }

        SwitchPortTuple tuple =
                        new SwitchPortTuple(sw, ps.getDesc().getPortNumber());
        boolean link_deleted  = false;

        lock.writeLock().lock();
        try {
            boolean topologyChanged = false;

            // if ps is a delete, or a modify where the port is down or
            // configured down
            if ((byte)OFPortReason.OFPPR_DELETE.ordinal() == ps.getReason() ||
                ((byte)OFPortReason.OFPPR_MODIFY.ordinal() ==
                                ps.getReason() && !portEnabled(ps.getDesc()))) {

                List<LinkTuple> eraseList = new ArrayList<LinkTuple>();
                if (this.portLinks.containsKey(tuple)) {
                    if (log.isDebugEnabled()) {
                        log.debug("handlePortStatus: Switch {} port #{} " +
                                  "reason {}; removing links",
                                  new Object[] {HexString.toHexString(sw.getId()),
                                                ps.getDesc().getPortNumber(),
                                                ps.getReason()});
                    }
                    eraseList.addAll(this.portLinks.get(tuple));
                    deleteLinks(eraseList, "Port Status Changed");
                    topologyChanged = true;
                    link_deleted    = true;
                }
            } else if (ps.getReason() ==
                                    (byte)OFPortReason.OFPPR_MODIFY.ordinal()) {
                // If ps is a port modification and the port state has changed
                // that affects links in the topology
                if (this.portLinks.containsKey(tuple)) {
                    for (LinkTuple link: this.portLinks.get(tuple)) {
                        LinkInfo linkInfo = links.get(link);
                        assert(linkInfo != null);
                        Integer updatedSrcPortState = null;
                        Integer updatedDstPortState = null;
                        if (link.getSrc().equals(tuple) &&
                                (linkInfo.getSrcPortState() !=
                                                    ps.getDesc().getState())) {
                            updatedSrcPortState = ps.getDesc().getState();
                            linkInfo.setSrcPortState(updatedSrcPortState);
                        }
                        if (link.getDst().equals(tuple) &&
                                (linkInfo.getDstPortState() !=
                                                    ps.getDesc().getState())) {
                            updatedDstPortState = ps.getDesc().getState();
                            linkInfo.setDstPortState(updatedDstPortState);
                        }
                        if ((updatedSrcPortState != null) ||
                                                (updatedDstPortState != null)) {
                            writeLink(link, linkInfo);
                            topologyChanged = true;
                        }
                    }
                }
            }

            if (topologyChanged) {
                //updateClusters();
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("handlePortStatus: Switch {} port #{} reason {};"+
                            " no links to update/remove",
                              new Object[] {HexString.toHexString(sw.getId()),
                                            ps.getDesc().getPortNumber(),
                                            ps.getReason()});
                }
            }
        } finally {
            lock.writeLock().unlock();
        }

        if (!link_deleted) {
            // Send LLDP right away when port state is changed for faster
            // cluster-merge. If it is a link delete then there is not need
            // to send the LLDPs right away and instead we wait for the LLDPs
            // to be sent on the timer as it is normally done
            sendLLDPs(); // do it outside the write-lock
        }
        return Command.CONTINUE;
    }

    /**
     * We send out LLDP messages when a switch is added to discover the topology
     * @param sw The IOFSwitch that connected to the controller
     */
    @Override
    public void addedSwitch(IOFSwitch sw) {
        // It's probably overkill to send LLDP from all switches, but we don't
        // know which switches might be connected to the new switch.
        // Need to optimize when supporting a large number of switches.
        sendLLDPs();
        // Update event history
        evHistTopoSwitch(sw, EvAction.SWITCH_CONNECTED, "None");
    }

    /**
     * When a switch disconnects we remove any links from our map and re-compute
     * the topology.
     * @param sw The IOFSwitch that disconnected from the controller
     */
    @Override
    public void removedSwitch(IOFSwitch sw) {
        // Update event history
        evHistTopoSwitch(sw, EvAction.SWITCH_DISCONNECTED, "None");
        List<LinkTuple> eraseList = new ArrayList<LinkTuple>();
        lock.writeLock().lock();
        try {
            if (switchLinks.containsKey(sw)) {
                // add all tuples with an endpoint on this switch to erase list
                eraseList.addAll(switchLinks.get(sw));
                deleteLinks(eraseList, "Switch Removed");
                //updateClusters();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Iterates though @SwitchCluster links and then deletes ones
     * that have timed out. The timeout is set by lldpTimeout.
     * If links are deleted updateClusters() is then called.
     */
    protected void timeoutLinks() {
        List<LinkTuple> eraseList = new ArrayList<LinkTuple>();
        Long curTime = System.currentTimeMillis();
        boolean linkChanged = false;

        // reentrant required here because deleteLink also write locks
        lock.writeLock().lock();
        try {
            Iterator<Entry<LinkTuple, LinkInfo>> it =
                                            this.links.entrySet().iterator();
            while (it.hasNext()) {
                Entry<LinkTuple, LinkInfo> entry = it.next();
                LinkTuple lt = entry.getKey();
                Long uTime = entry.getValue().getUnicastValidTime();
                Long mTime = entry.getValue().getMulticastValidTime();

                // Timeout the unicast and multicast LLDP valid times
                // independently.
                if ((uTime != null) && (uTime + this.lldpTimeout < curTime)){
                    entry.getValue().setUnicastValidTime(null);
                    uTime = null;
                    if (mTime != null)
                        addLinkToBroadcastDomain(lt);
                    // Note that even if mTime becomes null later on,
                    // the link would be deleted, which would trigger updateClusters().
                    linkChanged = true;
                }
                if ((mTime != null) && (mTime + this.lldpTimeout < curTime)) {
                    entry.getValue().setMulticastValidTime(null);
                    mTime = null;
                    // if uTime is not null, then link will remain as openflow
                    // link. If uTime is null, it will be deleted.  So, we
                    // don't care about linkChanged flag here.
                    removeLinkFromBroadcastDomain(lt);
                }
                // Add to the erase list only if both the unicast and multicast
                // times are null.
                if (uTime == null && mTime == null){
                    eraseList.add(entry.getKey());
                }
            }

            // if any link was deleted or any link was changed.
            if ((eraseList.size() > 0) || linkChanged) {
                deleteLinks(eraseList, "LLDP timeout");
                //updateClusters();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean portEnabled(OFPhysicalPort port) {
        if (port == null)
            return false;
        if ((OFPortConfig.OFPPC_PORT_DOWN.getValue() & port.getConfig()) > 0)
            return false;
        if ((OFPortState.OFPPS_LINK_DOWN.getValue() & port.getState()) > 0)
            return false;
        // Port STP state doesn't work with multiple VLANs, so ignore it for now
        //if ((port.getState() & OFPortState.OFPPS_STP_MASK.getValue()) == OFPortState.OFPPS_STP_BLOCK.getValue())
        //    return false;
        return true;
    }

    @Override
    public LinkInfo getLinkInfo(SwitchPortTuple idPort, boolean isSrcPort) {
        Set<LinkTuple> links = this.portLinks.get(idPort);
        if (links == null) {
            return null;
        }

        LinkTuple retLink = null;
        for (LinkTuple link : links) {
            if (log.isTraceEnabled()) {
                log.trace("getLinkInfo: check link {} against swPortTuple {}",
                          link, idPort);
            }
            if (link.getSrc().equals(idPort) && isSrcPort) {
                retLink = link;
            } else if (link.getDst().equals(idPort) && !isSrcPort) {
                retLink = link;
            }
        }

        LinkInfo linkInfo = null;
        if (retLink != null) {
            linkInfo = this.links.get(retLink);
        } else {
            log.debug("getLinkInfo: No link out of {} links is from port {}, "+
                    "isSrcPort {}",
                    new Object[] {links.size(), idPort, isSrcPort});
        }

        return linkInfo;
    }

    public Map<SwitchPortTuple, Set<LinkTuple>> getPortBroadcastDomainLinks() {
        return portBroadcastDomainLinks;
    }

    @Override
    public Map<LinkTuple, LinkInfo> getLinks() {
        lock.readLock().lock();
        Map<LinkTuple, LinkInfo> result;
        try {
            result = new HashMap<LinkTuple, LinkInfo>(links);
        } finally {
            lock.readLock().unlock();
        }
        return result;
    }

    protected void addLinkToBroadcastDomain(LinkTuple lt) {
        if (!portBroadcastDomainLinks.containsKey(lt.getSrc()))
            portBroadcastDomainLinks.put(lt.getSrc(), new HashSet<LinkTuple>());
        portBroadcastDomainLinks.get(lt.getSrc()).add(lt);

        if (!portBroadcastDomainLinks.containsKey(lt.getDst()))
            portBroadcastDomainLinks.put(lt.getDst(), new HashSet<LinkTuple>());
        portBroadcastDomainLinks.get(lt.getDst()).add(lt);
    }

    protected void removeLinkFromBroadcastDomain(LinkTuple lt) {

        if (portBroadcastDomainLinks.containsKey(lt.getSrc())) {
            portBroadcastDomainLinks.get(lt.getSrc()).remove(lt);
            if (portBroadcastDomainLinks.get(lt.getSrc()).isEmpty())
                portBroadcastDomainLinks.remove(lt.getSrc());
        }

        if (portBroadcastDomainLinks.containsKey(lt.getDst())) {
            portBroadcastDomainLinks.get(lt.getDst()).remove(lt);
            if (portBroadcastDomainLinks.get(lt.getDst()).isEmpty())
                portBroadcastDomainLinks.remove(lt.getDst());
        }
    }


    // STORAGE METHODS
    /**
     * Deletes all links from storage
     */
    void clearAllLinks() {
        storageSource.deleteRowsAsync(LINK_TABLE_NAME, null);
    }

    /**
     * Gets the storage key for a LinkTuple
     * @param lt The LinkTuple to get
     * @return The storage key as a String
     */
    private String getLinkId(LinkTuple lt) {
        String srcDpid = lt.getSrc().getSw().getStringId();
        String dstDpid = lt.getDst().getSw().getStringId();
        return srcDpid + "-" + lt.getSrc().getPort() + "-" +
            dstDpid + "-" + lt.getDst().getPort();
    }

    /**
     * Writes a LinkTuple and corresponding LinkInfo to storage
     * @param lt The LinkTuple to write
     * @param linkInfo The LinkInfo to write
     */
    void writeLink(LinkTuple lt, LinkInfo linkInfo) {
        if (linkInfo.getUnicastValidTime() != null) {
            Map<String, Object> rowValues = new HashMap<String, Object>();
            String id = getLinkId(lt);
            rowValues.put(LINK_ID, id);
            rowValues.put(LINK_VALID_TIME, linkInfo.getUnicastValidTime());
            String srcDpid = lt.getSrc().getSw().getStringId();
            rowValues.put(LINK_SRC_SWITCH, srcDpid);
            rowValues.put(LINK_SRC_PORT, lt.getSrc().getPort());
            if (linkInfo.linkStpBlocked()) {
                if (log.isTraceEnabled()) {
                    log.trace("writeLink, link {}, info {}, srcPortState Blocked",
                              lt, linkInfo);
                }
                rowValues.put(LINK_SRC_PORT_STATE,
                              OFPhysicalPort.OFPortState.OFPPS_STP_BLOCK.getValue());
            } else {
                if (log.isTraceEnabled()) {
                    log.trace("writeLink, link {}, info {}, srcPortState {}",
                              new Object[]{ lt, linkInfo, linkInfo.getSrcPortState() });
                }
                rowValues.put(LINK_SRC_PORT_STATE, linkInfo.getSrcPortState());
            }
            String dstDpid = lt.getDst().getSw().getStringId();
            rowValues.put(LINK_DST_SWITCH, dstDpid);
            rowValues.put(LINK_DST_PORT, lt.getDst().getPort());
            if (linkInfo.linkStpBlocked()) {
                if (log.isTraceEnabled()) {
                    log.trace("writeLink, link {}, info {}, dstPortState Blocked",
                              lt, linkInfo);
                }
                rowValues.put(LINK_DST_PORT_STATE,
                              OFPhysicalPort.OFPortState.OFPPS_STP_BLOCK.getValue());
            } else {
                if (log.isTraceEnabled()) {
                    log.trace("writeLink, link {}, info {}, dstPortState {}",
                              new Object[]{ lt, linkInfo, linkInfo.getDstPortState() });
                }
                rowValues.put(LINK_DST_PORT_STATE, linkInfo.getDstPortState());
            }
            storageSource.updateRowAsync(LINK_TABLE_NAME, rowValues);
        }
    }

    public Long readLinkValidTime(LinkTuple lt) {
        // FIXME: We're not currently using this right now, but if we start
        // to use this again, we probably shouldn't use it in its current
        // form, because it's doing synchronous storage calls. Depending
        // on the context this may still be OK, but if it's being called
        // on the packet in processing thread it should be reworked to
        // use asynchronous storage calls.
        Long validTime = null;
        IResultSet resultSet = null;
        try {
            String[] columns = { LINK_VALID_TIME };
            String id = getLinkId(lt);
            resultSet = storageSource.executeQuery(LINK_TABLE_NAME, columns,
                    new OperatorPredicate(LINK_ID, OperatorPredicate.Operator.EQ, id), null);
            if (resultSet.next())
                validTime = resultSet.getLong(LINK_VALID_TIME);
        }
        finally {
            if (resultSet != null)
                resultSet.close();
        }
        return validTime;
    }

    public void writeLinkInfo(LinkTuple lt, LinkInfo linkInfo) {
        if (linkInfo.getUnicastValidTime() != null) {
            Map<String, Object> rowValues = new HashMap<String, Object>();
            String id = getLinkId(lt);
            rowValues.put(LINK_ID, id);
            //LinkInfo linkInfo = links.get(lt);
            if (linkInfo.getUnicastValidTime() != null)
                rowValues.put(LINK_VALID_TIME, linkInfo.getUnicastValidTime());
            if (linkInfo.getSrcPortState() != null) {
                if (linkInfo != null && linkInfo.linkStpBlocked()) {
                    if (log.isTraceEnabled()) {
                        log.trace("writeLinkInfo, link {}, info {}, srcPortState Blocked",
                                  lt, linkInfo);
                    }
                    rowValues.put(LINK_SRC_PORT_STATE,
                                  OFPhysicalPort.OFPortState.OFPPS_STP_BLOCK.getValue());
                } else {
                    if (log.isTraceEnabled()) {
                        log.trace("writeLinkInfo, link {}, info {}",
                                  new Object[]{ lt, linkInfo});
                    }
                    rowValues.put(LINK_SRC_PORT_STATE, linkInfo.getSrcPortState());
                }
            }
            if (linkInfo.getDstPortState() != null) {
                if (linkInfo != null && linkInfo.linkStpBlocked()) {
                    if (log.isTraceEnabled()) {
                        log.trace("writeLinkInfo, link {}, info {}, dstPortState Blocked",
                                  lt, linkInfo);
                    }
                    rowValues.put(LINK_DST_PORT_STATE,
                                  OFPhysicalPort.OFPortState.OFPPS_STP_BLOCK.getValue());
                } else {
                    if (log.isTraceEnabled()) {
                        log.trace("writeLinkInfo, link {}, info {}",
                                  new Object[]{ lt, linkInfo});
                    }
                    rowValues.put(LINK_DST_PORT_STATE, linkInfo.getDstPortState());
                }
            }
            storageSource.updateRowAsync(LINK_TABLE_NAME, id, rowValues);
        }
    }

    /**
     * Removes a link from storage using an asynchronous call.
     * @param lt The LinkTuple to delete.
     */
    void removeLink(LinkTuple lt) {
        String id = getLinkId(lt);
        storageSource.deleteRowAsync(LINK_TABLE_NAME, id);
    }
    
    @Override
    public void addListener(ILinkDiscoveryListener listener) {
        linkDiscoveryAware.add(listener);
    }
    
//    @Override
    public void addListener(ITopologyListener listener) {
        topologyAware.add(listener);
    }

    /**
     * Register a link discovery aware component
     * @param linkDiscoveryAwareComponent
     */
    public void addLinkDiscoveryAware(ILinkDiscoveryListener linkDiscoveryAwareComponent) {
        // TODO make this a copy on write set or lock it somehow
        this.linkDiscoveryAware.add(linkDiscoveryAwareComponent);
    }

    /**
     * Deregister a link discovery aware component
     * @param linkDiscoveryAwareComponent
     */
    public void removeLinkDiscoveryAware(ILinkDiscoveryListener linkDiscoveryAwareComponent) {
        // TODO make this a copy on write set or lock it somehow
        this.linkDiscoveryAware.remove(linkDiscoveryAwareComponent);
    }
    
    
    /**
     * Register a topology aware component
     * @param topoAwareComponent
     */
    public void addTopologyAware(ITopologyListener topoAwareComponent) {
        // TODO make this a copy on write set or lock it somehow
        this.topologyAware.add(topoAwareComponent);
    }

    /**
     * Deregister a topology aware component
     * @param topoAwareComponent
     */
    public void removeTopologyAware(ITopologyListener topoAwareComponent) {
        // TODO make this a copy on write set or lock it somehow
        this.topologyAware.remove(topoAwareComponent);
    }

    /**
     * Sets the IStorageSource to use for ITology
     * @param storageSource the storage source to use
     */
    public void setStorageSource(IStorageSourceService storageSource) {
        this.storageSource = storageSource;
    }

    /**
     * Gets the storage source for this ITopology
     * @return The IStorageSource ITopology is writing to
     */
    public IStorageSourceService getStorageSource() {
        return storageSource;
    }

    /**
     * @param routingEngine the storage source to use for persisting link info
     */
    public void setRoutingEngine(IRoutingService routingEngine) {
        this.routingEngine = routingEngine;
    }

    @Override
    public boolean isCallbackOrderingPrereq(OFType type, String name) {
        return false;
    }

    @Override
    public boolean isCallbackOrderingPostreq(OFType type, String name) {
        return false;
    }

    @Override
    public void rowsModified(String tableName, Set<Object> rowKeys) {
        Map<Long, IOFSwitch> switches = floodlightProvider.getSwitches();
        ArrayList<IOFSwitch> updated_switches = new ArrayList<IOFSwitch>();
        for(Object key: rowKeys) {
            Long swId = new Long(HexString.toLong((String)key));
            if (switches.containsKey(swId)) {
                IOFSwitch sw = switches.get(swId);
                boolean curr_status = sw.hasAttribute(IOFSwitch.SWITCH_IS_CORE_SWITCH);
                boolean new_status =  false;
                IResultSet resultSet = null;

                try {
                    resultSet = storageSource.getRow(tableName, key);
                    for (Iterator<IResultSet> it = resultSet.iterator(); it.hasNext();) {
                        // In case of multiple rows, use the status in last row?
                        Map<String, Object> row = it.next().getRow();
                        if (row.containsKey(SWITCH_CORE_SWITCH)) {
                            new_status = ((String)row.get(SWITCH_CORE_SWITCH)).equals("true");
                        }
                    }
                }
                finally {
                    if (resultSet != null)
                        resultSet.close();
                }

                if (curr_status != new_status) {
                    updated_switches.add(sw);
                }
            } else {
                log.debug("Update for switch which has no entry in switch " +
                          "list (dpid={}), a delete action.", (String)key);
            }
        }

        for (IOFSwitch sw : updated_switches) {
            // Set SWITCH_IS_CORE_SWITCH to it's inverse value
            if (sw.hasAttribute(IOFSwitch.SWITCH_IS_CORE_SWITCH)) {
                sw.removeAttribute(IOFSwitch.SWITCH_IS_CORE_SWITCH);
                log.debug("SWITCH_IS_CORE_SWITCH set to False for {}", sw);
            }
            else {
                sw.setAttribute(IOFSwitch.SWITCH_IS_CORE_SWITCH, new Boolean(true));
                log.debug("SWITCH_IS_CORE_SWITCH set to True for {}", sw);
            }
            updates.add(new Update(sw));
        }

    }

    @Override
    public void rowsDeleted(String tableName, Set<Object> rowKeys) {
        // Ignore delete events, the switch delete will do the right thing on it's own
    }

    // IFloodlightModule classes
    
    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        Collection<Class<? extends IFloodlightService>> l = 
                new ArrayList<Class<? extends IFloodlightService>>();
        l.add(ILinkDiscoveryService.class);
        //l.add(ITopologyService.class);
        return l;
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService>
            getServiceImpls() {
        Map<Class<? extends IFloodlightService>,
        IFloodlightService> m = 
            new HashMap<Class<? extends IFloodlightService>,
                        IFloodlightService>();
        // We are the class that implements the service
        m.put(ILinkDiscoveryService.class, this);
        //m.put(ITopologyService.class, this);
        
        return m;
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        Collection<Class<? extends IFloodlightService>> l = 
                new ArrayList<Class<? extends IFloodlightService>>();
        l.add(IFloodlightProviderService.class);
        l.add(IStorageSourceService.class);
        l.add(IRoutingService.class);
        l.add(IRestApiService.class);
        return l;
    }

    @Override
    public void init(FloodlightModuleContext context)
                      throws FloodlightModuleException {
        floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
        storageSource = context.getServiceImpl(IStorageSourceService.class);
        routingEngine = context.getServiceImpl(IRoutingService.class);
        restApi = context.getServiceImpl(IRestApiService.class);
        
        // We create this here because there is no ordering guarantee
        this.topologyAware = new ArrayList<ITopologyListener>();
        this.linkDiscoveryAware = new ArrayList<ILinkDiscoveryListener>();
        this.lock = new ReentrantReadWriteLock();
        this.updates = new LinkedBlockingQueue<Update>();
        this.links = new HashMap<LinkTuple, LinkInfo>();
        this.portLinks = new HashMap<SwitchPortTuple, Set<LinkTuple>>();
        this.portBroadcastDomainLinks = new HashMap<SwitchPortTuple, Set<LinkTuple>>();
        this.switchLinks = new HashMap<IOFSwitch, Set<LinkTuple>>();
        this.evHistTopologySwitch =
            new EventHistory<EventHistoryTopologySwitch>("Topology: Switch");
        this.evHistTopologyLink =
            new EventHistory<EventHistoryTopologyLink>("Topology: Link");
        this.evHistTopologyCluster =
            new EventHistory<EventHistoryTopologyCluster>("Topology: Cluster");
    }

    @Override
    public void startUp(FloodlightModuleContext context) {
        // Create our storage tables
        storageSource.createTable(LINK_TABLE_NAME, null);
        storageSource.setTablePrimaryKeyName(LINK_TABLE_NAME, LINK_ID);
        storageSource.createTable(LINK_TABLE_NAME, null);
        storageSource.setTablePrimaryKeyName(LINK_TABLE_NAME, LINK_ID);
        // Register for storage updates for the switch table
        try {
            storageSource.addListener(SWITCH_TABLE_NAME, this);
        } catch (StorageException ex) {
            log.error("Error in installing listener for switch table - {}", SWITCH_TABLE_NAME);
        }
        
        ScheduledExecutorService ses = floodlightProvider.getScheduledExecutor();

        // Setup sending out LLDPs
        Runnable lldpSendTimer = new Runnable() {
            @Override
            public void run() {
                try {
                    sendLLDPs();

                    if (!shuttingDown) {
                        ScheduledExecutorService ses = 
                            floodlightProvider.getScheduledExecutor();
                                    ses.schedule(this, lldpFrequency, 
                                                        TimeUnit.MILLISECONDS);
                    }
                } catch (StorageException e) {
                    log.error("Storage exception in LLDP send timer; " + 
                              "terminating process", e);
                    floodlightProvider.terminate();
                } catch (Exception e) {
                    log.error("Exception in LLDP send timer", e);
                }
            }   
        };
        ses.schedule(lldpSendTimer, 1000, TimeUnit.MILLISECONDS);

        Runnable timeoutLinksTimer = new Runnable() {
            @Override
            public void run() {
                log.trace("Running timeoutLinksTimer");
                try {
                    timeoutLinks();
                    if (!shuttingDown) {
                        ScheduledExecutorService ses = 
                            floodlightProvider.getScheduledExecutor();
                        ses.schedule(this, lldpTimeout, TimeUnit.MILLISECONDS);
                    }
                } catch (StorageException e) {
                    log.error("Storage exception in link timer; " + 
                              "terminating process", e);
                    floodlightProvider.terminate();
                } catch (Exception e) {
                    log.error("Exception in timeoutLinksTimer", e);
                }
            }
        };
        ses.schedule(timeoutLinksTimer, 1000, TimeUnit.MILLISECONDS);

        updatesThread = new Thread(new Runnable () {
            @Override
            public void run() {
                while (true) {
                    try {
                        doUpdatesThread();
                    } catch (InterruptedException e) {
                        return;
                    } 
                }
            }}, "Topology Updates");
        updatesThread.start();
        
        // Register for the OpenFlow messages we want to receive
        floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
        floodlightProvider.addOFMessageListener(OFType.PORT_STATUS, this);
        // Register for switch updates
        floodlightProvider.addOFSwitchListener(this);
        floodlightProvider.addInfoProvider("summary", this);
        
        // init our rest api
        if (restApi != null) {
            restApi.addRestletRoutable(new TopologyWebRoutable());
        } else {
            log.error("Could not instantiate REST API");
        }

        setControllerTLV();
    }

    // ****************************************************
    // Topology Manager's Event History members and methods
    // ****************************************************

    // Topology Manager event history
    public EventHistory<EventHistoryTopologySwitch>  evHistTopologySwitch;
    public EventHistory<EventHistoryTopologyLink>    evHistTopologyLink;
    public EventHistory<EventHistoryTopologyCluster> evHistTopologyCluster;
    public EventHistoryTopologySwitch  evTopoSwitch;
    public EventHistoryTopologyLink    evTopoLink;
    public EventHistoryTopologyCluster evTopoCluster;

    // Switch Added/Deleted
    private void evHistTopoSwitch(IOFSwitch sw, EvAction actn, String reason) {
        if (evTopoSwitch == null) {
            evTopoSwitch = new EventHistoryTopologySwitch();
        }
        evTopoSwitch.dpid     = sw.getId();
        if ((sw.getChannel() != null) &&
            (SocketAddress.class.isInstance(
                sw.getChannel().getRemoteAddress()))) {
            evTopoSwitch.ipv4Addr =
                ((InetSocketAddress)(sw.getChannel().
                        getRemoteAddress())).getAddress().getAddress();
            evTopoSwitch.l4Port   =
                (short)(((InetSocketAddress)(sw.getChannel().
                        getRemoteAddress())).getPort());
        } else {
            byte[] zeroIpa = new byte[] {(byte)0, (byte)0, (byte)0, (byte)0};
            evTopoSwitch.ipv4Addr = zeroIpa;
            evTopoSwitch.l4Port = 0;
        }
        evTopoSwitch.reason   = reason;
        evTopoSwitch = evHistTopologySwitch.put(evTopoSwitch, actn);
    }

    private void evHistTopoLink(long srcDpid, long dstDpid, short srcPort,
            short dstPort, int srcPortState, int dstPortState,
            EvAction actn, String reason) {
        if (evTopoLink == null) {
            evTopoLink = new EventHistoryTopologyLink();
        }
        evTopoLink.srcSwDpid = srcDpid;
        evTopoLink.dstSwDpid = dstDpid;
        evTopoLink.srcSwport = srcPort;
        evTopoLink.dstSwport = dstPort;
        evTopoLink.srcPortState = srcPortState;
        evTopoLink.dstPortState = dstPortState;
        evTopoLink.reason    = reason;
        evTopoLink = evHistTopologyLink.put(evTopoLink, actn);
    }

    public void evHistTopoCluster(long dpid, long clusterIdOld,
                    long clusterIdNew, EvAction action, String reason) {
        if (evTopoCluster == null) {
            evTopoCluster = new EventHistoryTopologyCluster();
        }
        evTopoCluster.dpid         = dpid;
        evTopoCluster.clusterIdOld = clusterIdOld;
        evTopoCluster.clusterIdNew = clusterIdNew;
        evTopoCluster.reason       = reason;
        evTopoCluster = evHistTopologyCluster.put(evTopoCluster, action);
    }

    @Override
    public Map<String, Object> getInfo(String type) {
        if (!"summary".equals(type)) return null;

        Map<String, Object> info = new HashMap<String, Object>();

        int num_links = 0;
        for (Set<LinkTuple> links : switchLinks.values())
            num_links += links.size();
        info.put("# inter-switch links", num_links / 2);

        return info;
    }
}
