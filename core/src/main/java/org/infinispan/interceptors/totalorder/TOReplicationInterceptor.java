package org.infinispan.interceptors.totalorder;

import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.config.Configuration;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.interceptors.ReplicationInterceptor;
import org.infinispan.remoting.RpcException;
import org.infinispan.transaction.LocalTransaction;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

/**
 * Date: 1/16/12
 * Time: 10:51 AM
 *
 * @author pruivo
 */
public class TOReplicationInterceptor extends ReplicationInterceptor {

    private static final Log log = LogFactory.getLog(TOReplicationInterceptor.class);

    @Override
    protected void broadcastPrepare(TxInvocationContext context, PrepareCommand command) {
        boolean trace = log.isTraceEnabled();

        if(!command.isTotalOrdered()) {
            super.broadcastPrepare(context, command);
            return;
        }

        if(trace) {
            log.tracef("Broadcasting with Total Order the command %s", command);
        }

        boolean sync = configuration.getCacheMode() == Configuration.CacheMode.REPL_SYNC;
        rpcManager.broadcastRpcCommand(command, false);

        if(sync) {

            if(trace) {
                log.tracef("Command [%s] sent in synchronous mode. waiting until modification is applied",
                        command);
            }
            //this is only invoked in local context
            LocalTransaction localTransaction = (LocalTransaction) context.getCacheTransaction();
            try {
                localTransaction.awaitUntilModificationsApplied(configuration.getSyncReplTimeout());
            } catch (Throwable throwable) {
                throw new RpcException(throwable);
            } finally {
                if(trace) {
                    log.tracef("Command [%s] waiting finish",
                            command);
                }
            }
        }
    }
}
