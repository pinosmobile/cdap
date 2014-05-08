package com.continuuity.test.internal;

import com.continuuity.api.data.DataSet;
import com.continuuity.app.ApplicationSpecification;
import com.continuuity.app.program.Type;
import com.continuuity.common.lang.jar.JarClassLoader;
import com.continuuity.common.queue.QueueName;
import com.continuuity.data.DataFabric;
import com.continuuity.data.DataFabric2Impl;
import com.continuuity.data.DataSetAccessor;
import com.continuuity.data.dataset.DataSetInstantiator;
import com.continuuity.data2.transaction.TransactionContext;
import com.continuuity.data2.transaction.TransactionFailureException;
import com.continuuity.data2.transaction.TransactionSystemClient;
import com.continuuity.gateway.handlers.AppFabricHttpHandler;
import com.continuuity.test.ApplicationManager;
import com.continuuity.test.DataSetManager;
import com.continuuity.test.FlowManager;
import com.continuuity.test.MapReduceManager;
import com.continuuity.test.ProcedureClient;
import com.continuuity.test.ProcedureManager;
import com.continuuity.test.RuntimeStats;
import com.continuuity.test.StreamWriter;
import com.continuuity.test.internal.guice.AppFabricServiceWrapper;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.apache.twill.filesystem.Location;
import org.apache.twill.filesystem.LocationFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 *
 */
public class DefaultApplicationManager implements ApplicationManager {

  private final ConcurrentMap<String, ProgramId> runningProcessses = Maps.newConcurrentMap();
  private final String accountId;
  private final String applicationId;
  private final TransactionSystemClient txSystemClient;
  private final DataSetInstantiator dataSetInstantiator;
  private final StreamWriterFactory streamWriterFactory;
  private final ProcedureClientFactory procedureClientFactory;
  private final AppFabricHttpHandler httpHandler;


  @Inject
  public DefaultApplicationManager(LocationFactory locationFactory,
                                   DataSetAccessor dataSetAccessor,
                                   TransactionSystemClient txSystemClient,
                                   StreamWriterFactory streamWriterFactory,
                                   ProcedureClientFactory procedureClientFactory,
                                   @Assisted("accountId") String accountId,
                                   @Assisted("applicationId") String applicationId,
                                   @Assisted Location deployedJar,
                                   @Assisted ApplicationSpecification appSpec,
                                   AppFabricHttpHandler httpHandler) {
    this.accountId = accountId;
    this.applicationId = applicationId;
    this.streamWriterFactory = streamWriterFactory;
    this.procedureClientFactory = procedureClientFactory;
    this.txSystemClient = txSystemClient;
    this.httpHandler = httpHandler;

    DataFabric dataFabric = new DataFabric2Impl(locationFactory, dataSetAccessor);

    try {
      // Since we expose the DataSet class, it has to be loaded using ClassLoader delegation.
      // The drawback is we'll not be able to instrument DataSet classes using ASM.
      this.dataSetInstantiator = new DataSetInstantiator(dataFabric,
                                                         new DataSetClassLoader(new JarClassLoader(deployedJar)));
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
    this.dataSetInstantiator.setDataSets(appSpec.getDataSets().values());
  }

  @Override
  public FlowManager startFlow(final String flowName) {
    return startFlow(flowName, ImmutableMap.<String, String>of());
  }

  @Override
  public FlowManager startFlow(final String flowName, Map<String, String> arguments) {
    try {
      final ProgramId flowId = new ProgramId(applicationId, flowName, "flows");
      Preconditions.checkState(runningProcessses.putIfAbsent(flowName, flowId) == null,
                               "Flow %s is already running", flowName);
      try {
        AppFabricServiceWrapper.startProgram(httpHandler, applicationId, flowName, "flows", arguments);
      } catch (Exception e) {
        runningProcessses.remove(flowName);
        throw Throwables.propagate(e);
      }

      return new FlowManager() {
        @Override
        public void setFlowletInstances(String flowletName, int instances) {
          Preconditions.checkArgument(instances > 0, "Instance counter should be > 0.");
          try {
            AppFabricServiceWrapper.setFlowletInstances
              (httpHandler, applicationId, flowName, flowletName, instances);
          } catch (Exception e) {
            throw Throwables.propagate(e);
          }
        }

        @Override
        public void stop() {
          try {
            if (runningProcessses.remove(flowName, flowId)) {
              AppFabricServiceWrapper.stopProgram(httpHandler, applicationId, flowName, "flows");
            }
          } catch (Exception e) {
            throw Throwables.propagate(e);
          }
        }
      };
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public MapReduceManager startMapReduce(final String jobName) {
    return startMapReduce(jobName, ImmutableMap.<String, String>of());
  }

  @Override
  public MapReduceManager startMapReduce(final String jobName, Map<String, String> arguments) {
    try {

      final ProgramId jobId = new ProgramId(applicationId, jobName, "mapreduce");

      // mapreduce job can stop by itself, so refreshing info about its state
      if (!isRunning(jobId)) {
        runningProcessses.remove(jobName);
      }

      Preconditions.checkState(runningProcessses.putIfAbsent(jobName, jobId) == null,
                               "MapReduce job %s is already running", jobName);
      try {
        AppFabricServiceWrapper.startProgram(httpHandler, applicationId, jobName, "mapreduce", arguments);
      } catch (Exception e) {
        runningProcessses.remove(jobName);
        throw Throwables.propagate(e);
      }

      return new MapReduceManager() {
        @Override
        public void stop() {
          try {
            if (runningProcessses.remove(jobName, jobId)) {
              AppFabricServiceWrapper.stopProgram(httpHandler, applicationId, jobName, "mapreduce");
            }
          } catch (Exception e) {
            throw Throwables.propagate(e);
          }
        }

        @Override
        public void waitForFinish(long timeout, TimeUnit timeoutUnit) throws TimeoutException, InterruptedException {
          while (timeout > 0 && isRunning(jobId)) {
            timeoutUnit.sleep(1);
            timeout--;
          }

          if (timeout == 0 && isRunning(jobId)) {
            throw new TimeoutException("Time limit reached.");
          }

        }
      };
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public ProcedureManager startProcedure(final String procedureName) {
    return startProcedure(procedureName, ImmutableMap.<String, String>of());
  }

  @Override
  public ProcedureManager startProcedure(final String procedureName, Map<String, String> arguments) {
    try {
      final ProgramId procedureId = new ProgramId(applicationId, procedureName, "procedures");
      Preconditions.checkState(runningProcessses.putIfAbsent(procedureName, procedureId) == null,
                               "Procedure %s is already running", procedureName);
      try {
        AppFabricServiceWrapper.startProgram(httpHandler, applicationId, procedureName , "procedures", arguments);
      } catch (Exception e) {
        runningProcessses.remove(procedureName);
        throw Throwables.propagate(e);
      }

      return new ProcedureManager() {
        @Override
        public void stop() {
          try {
            if (runningProcessses.remove(procedureName, procedureId)) {
              AppFabricServiceWrapper.stopProgram(httpHandler, applicationId, procedureName , "procedures");
            }
          } catch (Exception e) {
            throw Throwables.propagate(e);
          }
        }

        @Override
        public ProcedureClient getClient() {
          return procedureClientFactory.create(accountId, applicationId, procedureName);
        }
      };
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public StreamWriter getStreamWriter(String streamName) {
    QueueName queueName = QueueName.fromStream(streamName);
    return streamWriterFactory.create(queueName, accountId, applicationId);
  }

  @Override
  public <T extends DataSet> DataSetManager<T> getDataSet(String dataSetName) {
    final T dataSet = dataSetInstantiator.getDataSet(dataSetName);

    try {
      final TransactionContext txContext =
        new TransactionContext(txSystemClient, dataSetInstantiator.getTransactionAware());
      txContext.start();
      return new DataSetManager<T>() {
        @Override
        public T get() {
          return dataSet;
        }

        @Override
        public void flush() {
          try {
            txContext.finish();
            txContext.start();
          } catch (TransactionFailureException e) {
            throw Throwables.propagate(e);
          }
        }
      };
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public void stopAll() {
    try {
      for (Map.Entry<String, ProgramId> entry : Iterables.consumingIterable(runningProcessses.entrySet())) {
        // have to do a check, since mapreduce jobs could stop by themselves earlier, and appFabricServer.stop will
        // throw error when you stop smth that is not running.
        if (isRunning(entry.getValue())) {
          ProgramId id = entry.getValue();
          AppFabricServiceWrapper.stopProgram(httpHandler, id.getApplicationId(),
                                              id.getRunnableId(), id.getRunnableType());
        }
      }
    } catch (Exception e) {
      throw Throwables.propagate(e);
    } finally {
      RuntimeStats.clearStats(applicationId);
    }
  }

  private static final class DataSetClassLoader extends ClassLoader {

    private final ClassLoader classLoader;

    private DataSetClassLoader(ClassLoader classLoader) {
      this.classLoader = classLoader;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
      return classLoader.loadClass(name);
    }
  }

  private class ProgramId {
    private final String appId;
    private final String runnableId;
    private final String runnableType;

    private ProgramId(String applicationId, String runnableId, String runnableType) {
      this.appId = applicationId;
      this.runnableId = runnableId;
      this.runnableType = runnableType;
    }
    public String getApplicationId() {
      return this.appId;
    }
    public String getRunnableId() {
      return this.runnableId;
    }
    public String getRunnableType() {
      return this.runnableType;
    }
  }

  private boolean isRunning(ProgramId programId) {
    try {

      String status = AppFabricServiceWrapper.getStatus(httpHandler, programId.getApplicationId(),
                                                        programId.getRunnableId(), programId.getRunnableType());
      // comparing to hardcoded string is ugly, but this is how appFabricServer works now to support legacy UI
      return "RUNNING".equals(status);
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }
}
