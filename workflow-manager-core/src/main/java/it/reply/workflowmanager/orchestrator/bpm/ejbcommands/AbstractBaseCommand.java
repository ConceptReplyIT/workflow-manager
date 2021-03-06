package it.reply.workflowmanager.orchestrator.bpm.ejbcommands;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;

import it.reply.workflowmanager.dsl.Error;
import it.reply.workflowmanager.dsl.ErrorCode;
import it.reply.workflowmanager.dsl.WorkflowErrorCode;
import it.reply.workflowmanager.logging.CustomLogger;
import it.reply.workflowmanager.logging.CustomLoggerFactory;
import it.reply.workflowmanager.orchestrator.bpm.WIHs.EJBWorkItemHelper;
import it.reply.workflowmanager.orchestrator.bpm.WIHs.misc.SignalEvent;
import it.reply.workflowmanager.utils.Constants;

import org.hibernate.StaleObjectStateException;
import org.kie.api.executor.CommandContext;
import org.kie.api.executor.ExecutionResults;
import org.kie.api.runtime.process.WorkItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.OptimisticLockException;
import javax.persistence.PersistenceException;

/**
 * Abstract base class for command implementation.<br/>
 * This sets up logger and some variables from the Executor command context. It also manages logging
 * at the start and end of command and provides helper methods for error and result handling.
 * 
 * @author l.biava
 * 
 */
public abstract class AbstractBaseCommand<T extends AbstractBaseCommand<T>>
    implements IEJBCommand<T> {

  private static final Logger LOG = LoggerFactory.getLogger(AbstractBaseCommand.class);

  protected final CustomLogger logger;
  
  private T self;

  @SuppressWarnings("unchecked")
  public AbstractBaseCommand() {
    logger = CustomLoggerFactory.getLogger(this.getClass());
    self = (T) this;
  }

  /**
   * Must implement custom execution logic.
   * 
   * @param ctx
   *          - contextual data given by the executor service
   * @return returns any results in case of successful execution
   * @throws Exception
   *           in case execution failed and shall be retried if possible
   */
  protected abstract ExecutionResults customExecute(CommandContext ctx) throws Exception;

  /**
   * Returns the proxy on which call business methods.
   */
  protected T getFacade() {
    return self;
  }

  /**
   * Set the proxy on which call business methods.
   */
  protected void setFacade(T facade) {
    this.self = Preconditions.checkNotNull(facade);
  }

  public static WorkItem getWorkItem(CommandContext ctx) {
    return (WorkItem) ctx.getData(Constants.WORKITEM);
  }

  @SuppressWarnings("unchecked")
  public static <C> Optional<C> getOptionalParameter(CommandContext ctx, String parameterName) {
    WorkItem wi = getWorkItem(ctx);
    Object parameter = wi.getParameter(parameterName);
    try {
      return Optional.fromNullable((C) parameter);
    } catch (ClassCastException ex) {
      LOG.error("Error retrieving parameter {} in WorkItem {}", parameterName, wi.getName(), ex);
      return Optional.absent();
    }
  }

  public static <C> C getParameter(CommandContext ctx, String parameterName) {
    return AbstractBaseCommand.<C>getOptionalParameter(ctx, parameterName).orNull();
  }

  public static <C> C getRequiredParameter(CommandContext ctx, String parameterName) {
    Optional<C> optionalValue = AbstractBaseCommand.getOptionalParameter(ctx, parameterName);
    if (optionalValue.isPresent()) {
      return optionalValue.get();
    } else {
      throw new IllegalArgumentException(
          String.format("WF parameter with name <%s> cannot be null", parameterName));
    }
  }

  public static Long getProcessInstanceId(CommandContext ctx) {
    return EJBWorkItemHelper.getProcessInstanceId(ctx);
  }

  public static it.reply.workflowmanager.dsl.Error getErrorResult(CommandContext ctx) {
    return (it.reply.workflowmanager.dsl.Error) getWorkItem(ctx)
        .getParameter(Constants.ERROR_RESULT);
  }

  /**
   * <b>This method SHOULD NOT be overridden !</b> <br/>
   * Use the {@link BaseCommand#customExecute(CommandContext)} method for the command logic.
   */
  @Override
  public ExecutionResults execute(CommandContext ctx) throws Exception {

    // If the command is not an EJB an IllegalStateException will be thrown
    T proxyCommand = getFacade();

    logCommandStarted(ctx);

    ExecutionResults exRes = new ExecutionResults();
    int maxNumOfTries = 1;
    if (this instanceof RetriableCommand) {
      maxNumOfTries = ((RetriableCommand) this).getNumOfMaxRetries(ctx);
      if (maxNumOfTries <= 0) {
        throw new IllegalArgumentException("Max num of retries must be > 0");
      }
    }

    int tries = 0;
    boolean retryError;
    do {
      retryError = false;
      try {
        if (tries == 0) {
          exRes = proxyCommand.customExecute(ctx);
        } else {
          logCommandRetry(exRes, tries, maxNumOfTries);
          exRes = ((RetriableCommand) proxyCommand).retry(ctx);
        }
      } catch (Exception e) {
        boolean persistenceExceptionFound = false;
        Throwable cause = e;
        while (cause != null) {
          if (cause instanceof PersistenceException || cause instanceof StaleObjectStateException) {
            handlePersistenceException((Exception) cause, exRes);
            persistenceExceptionFound = true;
            break;
          } else {
            cause = !cause.equals(cause.getCause()) ? cause.getCause() : null;
          }
        }
        if (!persistenceExceptionFound) {
          throw e;
        }
      }
      SignalEvent<?> signalEvent = (SignalEvent<?>) exRes.getData(Constants.SIGNAL_EVENT);
      if (signalEvent != null && signalEvent.getType() == SignalEvent.SignalEventType.ERROR
          && signalEvent.getSubType() == SignalEvent.SignalEventSubType.PERSISTENCE) {
        retryError = true;
      }
    } while (retryError && ++tries < maxNumOfTries);

    logCommandEnded(exRes);

    return exRes;
  }

  /**
   * Logs command started info.
   */
  protected void logCommandStarted(final CommandContext ctx) {
    if (logger.isInfoEnabled()) {
      String WIId = String.valueOf(getWorkItem(ctx).getId());
      String tag = "(Task: " + this.getClass().getName() + ", PID: " + getProcessInstanceId(ctx)
          + ", WIId: " + WIId + ", params: " + getWorkItem(ctx).getParameters() + ") - ";
      logger.setTag(tag);
      logger.info("STARTED");
    }
  }

  /**
   * Logs command retry info.
   */
  protected void logCommandRetry(final ExecutionResults exResults, int tryNum, int maxNumOfTries) {
    if (logger.isInfoEnabled()) {
      try {
        StringBuilder sb = new StringBuilder();
        sb.append("RETRY, Try ").append(tryNum).append(" of ").append(maxNumOfTries).append(".");
        @SuppressWarnings("unchecked")
        SignalEvent<Error> signalEvent =
            (SignalEvent<Error>) exResults.getData(Constants.SIGNAL_EVENT);
        if (signalEvent != null && signalEvent.getPayload() != null) {
          sb.append("\nRetry caused by:\n")
              .append(signalEvent.getPayload().getVerbose())
              .append("\n");
        }
        logger.info(sb.toString());
      } catch (Exception ex) {
        LOG.warn("Cannot log EJBCommand result.", ex);
      }
    }
  }

  /**
   * Logs command ended info.
   */
  protected void logCommandEnded(final ExecutionResults exResults) {
    if (logger.isInfoEnabled()) {
      try {
        logger
            .info("ENDED, ResultStatus(" + exResults.getData(Constants.RESULT_STATUS) + "), Result("
                + (exResults.getData("Result") != null ? exResults.getData("Result") : "") + ")");
      } catch (Exception ex) {
        LOG.warn("Cannot log EJBCommand result.", ex);
      }
    }
  }

  protected ExecutionResults handlePersistenceException(Exception ex, ExecutionResults exResults) {
    SignalEvent<Error> signalEvent = new SignalEvent<Error>(SignalEvent.SignalEventType.ERROR,
        SignalEvent.SignalEventSubType.PERSISTENCE);
    StringBuilder verbose = new StringBuilder("Unable to perform the operation");
    boolean optLock;
    if (ex instanceof OptimisticLockException) {
      optLock = true;
      verbose.append(" because of conflicting operations on entity ");
      Object entity = ((OptimisticLockException) ex).getEntity();
      if (entity != null) {
        verbose.append(entity.getClass().getCanonicalName());
      } else {
        verbose.append("UNKNOWN");
      }
    } else if (ex instanceof StaleObjectStateException) {
      optLock = true;
      verbose.append(" because of conflicting operations on entity ");
      String entityName = ((StaleObjectStateException) ex).getEntityName();
      if (!Strings.isNullOrEmpty(entityName)) {
        verbose.append(entityName);
      } else {
        verbose.append("UNKNOWN");
      }
    } else {
      optLock = false;
    }
    ErrorCode errorCode = optLock ? WorkflowErrorCode.ORC_CONFLICTING_CONCURRENT_OPERATIONS
        : WorkflowErrorCode.ORC_PERSISTENCE_ERROR;
    Error error = generateError(errorCode);
    error.setVerbose(verbose.toString());
    signalEvent.setPayload(error);
    return errorOccurred(signalEvent, exResults);
  }

  protected ExecutionResults errorOccurred(ErrorCode errorCode, String verbose,
      ExecutionResults exResults) {
    Error error = generateError(errorCode);
    error.setVerbose(verbose);
    return errorOccurred(error, exResults);
  }

  protected ExecutionResults errorOccurred(ErrorCode errorCode, Throwable t,
      ExecutionResults exResults) {
    Error error = generateError(errorCode);
    error.setVerbose(t);
    return errorOccurred(error, exResults);
  }

  protected ExecutionResults errorOccurred(Error error, ExecutionResults exResults) {
    SignalEvent<Error> signalEvent = new SignalEvent<Error>(SignalEvent.SignalEventType.ERROR,
        SignalEvent.SignalEventSubType.GENERIC);
    signalEvent.setPayload(error);
    return errorOccurred(signalEvent, exResults);
  }

  protected ExecutionResults errorOccurred(SignalEvent<Error> signalEvent,
      ExecutionResults exResults) {

    Error error = signalEvent.getPayload();
    exResults.setData(Constants.RESULT_STATUS, "ERROR");
    exResults.setData(Constants.ERROR_RESULT, error);
    exResults.setData(Constants.SIGNAL_EVENT, signalEvent);

    logger.error("ERROR: {}", error);

    return exResults;
  }

  /**
   * Helper method to set the result output variable of the command.
   * 
   * @param result
   *          the result data.
   * @param exResults
   *          {@link ExecutionResults} of the command for creating the result output variable.
   */
  protected <ResultType> ExecutionResults resultOccurred(ResultType result,
      ExecutionResults exResults) {
    exResults.setData(Constants.RESULT_STATUS, "OK");
    exResults.setData(Constants.OK_RESULT, result);
    return exResults;
  }

  protected <ResultType> ExecutionResults resultOccurred(ResultType result) {
    ExecutionResults exResults = new ExecutionResults();
    exResults.setData(Constants.RESULT_STATUS, "OK");
    exResults.setData(Constants.OK_RESULT, result);
    return exResults;
  }

  protected it.reply.workflowmanager.dsl.Error generateError(ErrorCode errorCode) {
    return new it.reply.workflowmanager.dsl.Error(errorCode);
  }
}
