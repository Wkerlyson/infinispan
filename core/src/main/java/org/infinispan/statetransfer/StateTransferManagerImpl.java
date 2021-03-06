package org.infinispan.statetransfer;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.infinispan.commands.TopologyAffectedCommand;
import org.infinispan.commons.CacheException;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.distribution.DistributionManager;
import org.infinispan.distribution.LocalizedCacheTopology;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.distribution.ch.ConsistentHashFactory;
import org.infinispan.distribution.ch.KeyPartitioner;
import org.infinispan.distribution.ch.impl.ScatteredConsistentHashFactory;
import org.infinispan.distribution.ch.impl.SyncConsistentHashFactory;
import org.infinispan.distribution.ch.impl.SyncReplicatedConsistentHashFactory;
import org.infinispan.distribution.ch.impl.TopologyAwareSyncConsistentHashFactory;
import org.infinispan.distribution.group.impl.PartitionerConsistentHash;
import org.infinispan.factories.KnownComponentNames;
import org.infinispan.factories.annotations.ComponentName;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.factories.annotations.Stop;
import org.infinispan.globalstate.GlobalStateManager;
import org.infinispan.globalstate.ScopedPersistentState;
import org.infinispan.notifications.cachelistener.CacheNotifier;
import org.infinispan.partitionhandling.impl.PartitionHandlingManager;
import org.infinispan.persistence.manager.PreloadManager;
import org.infinispan.remoting.inboundhandler.DeliverOrder;
import org.infinispan.remoting.inboundhandler.PerCacheInboundInvocationHandler;
import org.infinispan.remoting.responses.Response;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.remoting.rpc.RpcOptions;
import org.infinispan.remoting.transport.Address;
import org.infinispan.topology.CacheJoinInfo;
import org.infinispan.topology.CacheTopology;
import org.infinispan.topology.CacheTopologyHandler;
import org.infinispan.topology.LocalTopologyManager;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

/**
 * {@link StateTransferManager} implementation.
 *
 * @author anistor@redhat.com
 * @since 5.2
 */
public class StateTransferManagerImpl implements StateTransferManager {

   private static final Log log = LogFactory.getLog(StateTransferManagerImpl.class);
   private static final boolean trace = log.isTraceEnabled();

   @ComponentName(KnownComponentNames.CACHE_NAME)
   @Inject protected String cacheName;
   @Inject private StateConsumer stateConsumer;
   @Inject private StateProvider stateProvider;
   @Inject private PartitionHandlingManager partitionHandlingManager;
   @Inject private DistributionManager distributionManager;
   @Inject private CacheNotifier cacheNotifier;
   @Inject private Configuration configuration;
   @Inject private GlobalConfiguration globalConfiguration;
   @Inject private RpcManager rpcManager;
   @Inject private LocalTopologyManager localTopologyManager;
   @Inject private KeyPartitioner keyPartitioner;
   @Inject private GlobalStateManager globalStateManager;
   // Only join the cluster after preloading
   @Inject private PreloadManager preloadManager;
   // Make sure we can handle incoming requests before joining
   @Inject private PerCacheInboundInvocationHandler inboundInvocationHandler;

   private Optional<Integer> persistentStateChecksum;

   private final CountDownLatch initialStateTransferComplete = new CountDownLatch(1);

   @Start(priority = 60)
   @Override
   public void start() throws Exception {
      if (trace) {
         log.tracef("Starting StateTransferManager of cache %s on node %s", cacheName, rpcManager.getAddress());
      }

      if (globalStateManager != null) {
         persistentStateChecksum = globalStateManager.readScopedState(cacheName).map(ScopedPersistentState::getChecksum);
      } else {
         persistentStateChecksum = Optional.empty();
      }

      float capacityFactor = globalConfiguration.isZeroCapacityNode() ? 0.0f : configuration.clustering().hash().capacityFactor();

      CacheJoinInfo joinInfo = new CacheJoinInfo(pickConsistentHashFactory(globalConfiguration, configuration),
            configuration.clustering().hash().hash(),
            configuration.clustering().hash().numSegments(),
            configuration.clustering().hash().numOwners(),
            configuration.clustering().stateTransfer().timeout(),
            configuration.transaction().transactionProtocol().isTotalOrder(),
            configuration.clustering().cacheMode(),
            capacityFactor,
            localTopologyManager.getPersistentUUID(),
            persistentStateChecksum);

      CacheTopology initialTopology = localTopologyManager.join(cacheName, joinInfo, new CacheTopologyHandler() {
         @Override
         public void updateConsistentHash(CacheTopology cacheTopology) {
            doTopologyUpdate(cacheTopology, false);
         }

         @Override
         public void rebalance(CacheTopology cacheTopology) {
            doTopologyUpdate(cacheTopology, true);
         }
      }, partitionHandlingManager);

      if (trace) {
         log.tracef("StateTransferManager of cache %s on node %s received initial topology %s", cacheName, rpcManager.getAddress(), initialTopology);
      }
   }

   /**
    * If no ConsistentHashFactory was explicitly configured we choose a suitable one based on cache mode.
    */
   public static ConsistentHashFactory pickConsistentHashFactory(GlobalConfiguration globalConfiguration, Configuration configuration) {
      ConsistentHashFactory factory = configuration.clustering().hash().consistentHashFactory();
      if (factory == null) {
         CacheMode cacheMode = configuration.clustering().cacheMode();
         if (cacheMode.isClustered()) {
            if (cacheMode.isDistributed()) {
               if (globalConfiguration.transport().hasTopologyInfo()) {
                  factory = new TopologyAwareSyncConsistentHashFactory();
               } else {
                  factory = new SyncConsistentHashFactory();
               }
            } else if (cacheMode.isReplicated() || cacheMode.isInvalidation()) {
               factory = new SyncReplicatedConsistentHashFactory();
            } else if (cacheMode.isScattered()) {
               factory = new ScatteredConsistentHashFactory();
            } else {
               throw new CacheException("Unexpected cache mode: " + cacheMode);
            }
         }
      }
      return factory;
   }

   /**
    * Decorates the given cache topology to add a key partitioner.
    *
    * The key partitioner may include support for grouping as well.
    */
   private CacheTopology addPartitioner(CacheTopology cacheTopology) {
      ConsistentHash currentCH = cacheTopology.getCurrentCH();
      currentCH = new PartitionerConsistentHash(currentCH, keyPartitioner);
      ConsistentHash pendingCH = cacheTopology.getPendingCH();
      if (pendingCH != null) {
         pendingCH = new PartitionerConsistentHash(pendingCH, keyPartitioner);
      }
      ConsistentHash unionCH = cacheTopology.getUnionCH();
      if (unionCH != null) {
         unionCH = new PartitionerConsistentHash(unionCH, keyPartitioner);
      }
      return new CacheTopology(cacheTopology.getTopologyId(), cacheTopology.getRebalanceId(), currentCH, pendingCH,
            unionCH, cacheTopology.getPhase(), cacheTopology.getActualMembers(), cacheTopology.getMembersPersistentUUIDs());
   }

   private void doTopologyUpdate(CacheTopology newCacheTopology, boolean isRebalance) {
      CacheTopology oldCacheTopology = distributionManager.getCacheTopology();

      int newTopologyId = newCacheTopology.getTopologyId();
      if (oldCacheTopology != null && oldCacheTopology.getTopologyId() > newTopologyId) {
         throw new IllegalStateException("Old topology is higher: old=" + oldCacheTopology + ", new=" + newCacheTopology);
      }

      if (trace) {
         log.tracef("Installing new cache topology %s on cache %s", newCacheTopology, cacheName);
      }

      // No need for extra synchronization here, since LocalTopologyManager already serializes topology updates.\
      if (newCacheTopology.getMembers().contains(rpcManager.getAddress())) {
         if (!distributionManager.getCacheTopology().isConnected() || !distributionManager.getCacheTopology().getMembersSet().contains(rpcManager.getAddress())) {
            if (trace) log.tracef("This is the first topology %d in which the local node is a member", newTopologyId);
            inboundInvocationHandler.setFirstTopologyAsMember(newTopologyId);
         }
      }

      // handle the partitioner
      newCacheTopology = addPartitioner(newCacheTopology);
      int newRebalanceId = newCacheTopology.getRebalanceId();
      CacheTopology.Phase phase = newCacheTopology.getPhase();

      cacheNotifier.notifyTopologyChanged(oldCacheTopology, newCacheTopology, newTopologyId, true);

      CompletableFuture<Void> consumerFuture = stateConsumer.onTopologyUpdate(newCacheTopology, isRebalance);
      CompletableFuture<Void> providerFuture = stateProvider.onTopologyUpdate(newCacheTopology, isRebalance);
      CompletableFuture.allOf(consumerFuture, providerFuture).thenRun(() -> {
         switch (phase) {
            case TRANSITORY:
            case READ_OLD_WRITE_ALL:
            case READ_ALL_WRITE_ALL:
            case READ_NEW_WRITE_ALL:
               localTopologyManager.confirmRebalancePhase(cacheName, newTopologyId, newRebalanceId, null);
         }
      });

      cacheNotifier.notifyTopologyChanged(oldCacheTopology, newCacheTopology, newTopologyId, false);

      if (initialStateTransferComplete.getCount() > 0) {
         assert distributionManager.getCacheTopology().getTopologyId() == newCacheTopology.getTopologyId();
         boolean isJoined = phase == CacheTopology.Phase.NO_REBALANCE
               && newCacheTopology.getReadConsistentHash().getMembers().contains(rpcManager.getAddress());
         if (isJoined) {
            initialStateTransferComplete.countDown();
            log.tracef("Initial state transfer complete for cache %s on node %s", cacheName, rpcManager.getAddress());
         }
      }
      partitionHandlingManager.onTopologyUpdate(newCacheTopology);
   }

   @Override
   public void waitForInitialStateTransferToComplete() {
      if (configuration.clustering().stateTransfer().awaitInitialTransfer()) {
         try {
            if (!localTopologyManager.isCacheRebalancingEnabled(cacheName)) {
               initialStateTransferComplete.countDown();
            }
            if (trace)
               log.tracef("Waiting for initial state transfer to finish for cache %s on %s", cacheName,
                          rpcManager.getAddress());
            boolean success = initialStateTransferComplete.await(configuration.clustering().stateTransfer().timeout(), TimeUnit.MILLISECONDS);
            if (!success) {
               throw new CacheException(String.format("Initial state transfer timed out for cache %s on %s",
                                                      cacheName, rpcManager.getAddress()));
            }
         } catch (CacheException e) {
            throw e;
         } catch (Exception e) {
            throw new CacheException(e);
         }
      }
   }

   @Stop(priority = 0)
   @Override
   public void stop() {
      if (trace) {
         log.tracef("Shutting down StateTransferManager of cache %s on node %s", cacheName, rpcManager.getAddress());
      }
      initialStateTransferComplete.countDown();
      localTopologyManager.leave(cacheName, configuration.clustering().remoteTimeout());
   }

   @Override
   public boolean isJoinComplete() {
      return distributionManager.getCacheTopology() != null; // TODO [anistor] this does not mean we have received a topology update or a rebalance yet
   }

   @Override
   public String getRebalancingStatus() throws Exception {
      return localTopologyManager.getRebalancingStatus(cacheName).toString();
   }

   @Override
   public boolean isStateTransferInProgress() {
      return stateConsumer.isStateTransferInProgress();
   }

   @Override
   public boolean isStateTransferInProgressForKey(Object key) {
      return stateConsumer.isStateTransferInProgressForKey(key);
   }

   @Override
   public CacheTopology getCacheTopology() {
      return distributionManager.getCacheTopology();
   }

   @Override
   public Map<Address, Response> forwardCommandIfNeeded(TopologyAffectedCommand command, Set<Object> affectedKeys,
                                                        Address origin) {
      LocalizedCacheTopology cacheTopology = distributionManager.getCacheTopology();
      if (cacheTopology == null) {
         if (trace) {
            log.tracef("Not fowarding command %s because topology is null.", command);
         }
         return Collections.emptyMap();
      }
      int cmdTopologyId = command.getTopologyId();
      // forward commands with older topology ids to their new targets
      // but we need to make sure we have the latest topology
      int localTopologyId = cacheTopology.getTopologyId();
      // if it's a tx/lock/write command, forward it to the new owners
      if (trace) {
         log.tracef("CommandTopologyId=%s, localTopologyId=%s", cmdTopologyId, localTopologyId);
      }

      if (cmdTopologyId < localTopologyId) {
         Collection<Address> newTargets = new HashSet<>(cacheTopology.getWriteOwners(affectedKeys));
         newTargets.remove(rpcManager.getAddress());
         // Forwarding to the originator would create a cycle
         // TODO This may not be the "real" originator, but one of the original recipients
         // or even one of the nodes that one of the original recipients forwarded the command to.
         // In non-transactional caches, the "real" originator keeps a lock for the duration
         // of the RPC, so this means we could get a deadlock while forwarding to it.
         newTargets.remove(origin);
         if (!newTargets.isEmpty()) {
            // Update the topology id to prevent cycles
            command.setTopologyId(localTopologyId);
            if (trace) {
               log.tracef("Forwarding command %s to new targets %s", command, newTargets);
            }
            final RpcOptions rpcOptions = rpcManager.getDefaultRpcOptions(false, DeliverOrder.NONE);
            // TODO find a way to forward the command async if it was received async
            // TxCompletionNotificationCommands are the only commands forwarded asynchronously, and they must be OOB
            return rpcManager.invokeRemotely(newTargets, command, rpcOptions);
         }
      }
      return Collections.emptyMap();
   }

   // TODO Investigate merging ownsData() and getFirstTopologyAsMember(), as they serve a similar purpose
   @Override
   public boolean ownsData() {
      return stateConsumer.ownsData();
   }

   @Override
   public int getFirstTopologyAsMember() {
      return inboundInvocationHandler.getFirstTopologyAsMember();
   }

   @Override

   public String toString() {
      return "StateTransferManagerImpl [" + cacheName + "@" + rpcManager.getAddress() + "]";
   }
}
