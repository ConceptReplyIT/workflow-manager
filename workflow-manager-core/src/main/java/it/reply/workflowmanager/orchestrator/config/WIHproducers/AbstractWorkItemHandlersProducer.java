package it.reply.workflowmanager.orchestrator.config.WIHproducers;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import it.reply.workflowmanager.orchestrator.bpm.WIHs.AsyncEJBWorkItemHandler;
import it.reply.workflowmanager.orchestrator.bpm.WIHs.SyncEJBWorkItemHandler;
import it.reply.workflowmanager.orchestrator.bpm.commands.DispatcherCommand;
import it.reply.workflowmanager.orchestrator.config.ConfigProducer;
import it.reply.workflowmanager.utils.Constants;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jbpm.executor.commands.LogCleanupCommand;
import org.kie.api.runtime.process.WorkItemHandler;
import org.kie.api.executor.CommandContext;
import org.kie.api.executor.ExecutorService;
import org.kie.api.executor.RequestInfo;
import org.kie.api.executor.STATUS;
import org.kie.internal.runtime.manager.WorkItemHandlerProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Producer of {@link WorkItemHandler}s to bind jBPM task to commands.
 * 
 * @author l.biava
 *
 */
public abstract class AbstractWorkItemHandlersProducer implements WorkItemHandlerProducer {

  private static final Logger LOG = LoggerFactory.getLogger(AbstractWorkItemHandlersProducer.class);

  protected abstract Class<? extends DispatcherCommand> getDistpacherCommandClass();

  private ExecutorService executorService;

  protected void setExecutorService(ExecutorService executorService,
      ConfigProducer configProducer) {
    executorService.setThreadPoolSize(configProducer.getExecutorServiceThreadPoolSize());
    executorService.setInterval(configProducer.getExecutorServiceInterval());
    executorService.setRetries(configProducer.getExecutorServiceRetries());
    this.executorService = executorService;
    if (!executorService.isActive()) {
      // TODO is it right to initialize it here?
      LOG.info("Initializing ExecutorService.");
      scheduleDBCleanUp(executorService, configProducer.getCleanUpOlderThanPeriod(),
          configProducer.getCleanUpSchedulingPeriod(), configProducer.getCleanUpFirstRunDelay());
      executorService.init();
    }
  }

  // Schedule delay of the first run of the LogCleanupCommand (in seconds)
  private final static int firstRunDelay = 120;

  // Scheduling period of the LogCleanupCommand (iTimer expression)
  private final static String schedulingPeriod = "24h";

  // LogCleanupCommand will clean records older than the actual time
  // subtracted this value (Timer expression)
  private final static String olderThanPeriod = "30d";

  @Override
  public Map<String, WorkItemHandler> getWorkItemHandlers(final String identifier,
      final Map<String, Object> params) {
    final Map<String, WorkItemHandler> workItemHandlers = new HashMap<String, WorkItemHandler>();

    AsyncEJBWorkItemHandler EJBasyncWIH = new AsyncEJBWorkItemHandler(executorService,
        getDistpacherCommandClass());

    workItemHandlers.put(Constants.ASYNC_WIH_NAME, EJBasyncWIH);

    SyncEJBWorkItemHandler syncEJBWorkItemHandler = new SyncEJBWorkItemHandler(
        getDistpacherCommandClass());
    workItemHandlers.put(Constants.SYNC_WIH_NAME, syncEJBWorkItemHandler);

    return workItemHandlers;
  }

  /**
   * Cleans the Jbpm audit tables.
   * 
   * @param executorService
   *          must not be initialized in orded to cancel pending requests
   */
  private static void scheduleDBCleanUp(ExecutorService executorService, String olderThanPeriod,
      String schedulingPeriod, Integer firstRunDelay) {
    if (executorService.isActive()) {
      throw new IllegalStateException("ExecutorService must not be initialized.");
    }
    String commandId = LogCleanupCommand.class.getName();

    CommandContext ctx = new CommandContext();
    ctx.setData("OlderThanPeriod", MoreObjects.firstNonNull(olderThanPeriod,
        AbstractWorkItemHandlersProducer.olderThanPeriod));
    ctx.setData("NextRun", MoreObjects.firstNonNull(schedulingPeriod,
        AbstractWorkItemHandlersProducer.schedulingPeriod));

    // if there is already a pending request with the same parameters keep it and cancel the others,
    // otherwise cancel everything
    RequestInfo okReq = null;

    List<RequestInfo> prevReqs = executorService.getRequestsByCommand(commandId, null);

    for (RequestInfo req : prevReqs) {
      switch (req.getStatus()) {
        case ERROR:
        case RETRYING:
        case QUEUED:
        case RUNNING:
          if (req.getStatus() == STATUS.ERROR) {
            executorService.cancelRequest(req.getId());
          } else {
            if (okReq == null) {
              ByteArrayInputStream bis = new ByteArrayInputStream(req.getRequestData());
              CommandContext oldCtx = null;
              try {
                ObjectInputStream ois = new ObjectInputStream(bis);
                oldCtx = (CommandContext) ois.readObject();
              } catch (Exception e) {
                executorService.cancelRequest(req.getId());
                continue;
              }
              if (oldCtx != null && oldCtx.getData().equals(ctx.getData())) {
                okReq = req;
                continue;
              } else {
                executorService.cancelRequest(req.getId());
              }
            } else {
              executorService.cancelRequest(req.getId());
            }
          }
          break;
        case CANCELLED:
        case DONE:
        default:
          break;
      }
    }
    if (okReq == null) {
      Calendar cal = Calendar.getInstance();
      cal.add(Calendar.SECOND, MoreObjects.firstNonNull(firstRunDelay,
          AbstractWorkItemHandlersProducer.firstRunDelay));
      executorService.scheduleRequest(commandId, cal.getTime(), ctx);
    }
  }
}
