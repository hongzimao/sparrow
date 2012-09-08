package edu.berkeley.sparrow.daemon.scheduler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.configuration.Configuration;
import org.apache.log4j.Logger;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import edu.berkeley.sparrow.daemon.SparrowConf;
import edu.berkeley.sparrow.daemon.util.Logging;
import edu.berkeley.sparrow.daemon.util.Network;
import edu.berkeley.sparrow.daemon.util.Serialization;
import edu.berkeley.sparrow.daemon.util.ThriftClientPool;
import edu.berkeley.sparrow.thrift.FrontendService;
import edu.berkeley.sparrow.thrift.FrontendService.AsyncClient.frontendMessage_call;
import edu.berkeley.sparrow.thrift.InternalService;
import edu.berkeley.sparrow.thrift.InternalService.AsyncClient;
import edu.berkeley.sparrow.thrift.InternalService.AsyncClient.enqueueTaskReservations_call;
import edu.berkeley.sparrow.thrift.TEnqueueTaskReservationsRequest;
import edu.berkeley.sparrow.thrift.TFullTaskId;
import edu.berkeley.sparrow.thrift.THostPort;
import edu.berkeley.sparrow.thrift.TPlacementPreference;
import edu.berkeley.sparrow.thrift.TSchedulingRequest;
import edu.berkeley.sparrow.thrift.TTaskLaunchSpec;
import edu.berkeley.sparrow.thrift.TTaskSpec;

/**
 * This class implements the Sparrow scheduler functionality.
 */
public class Scheduler {
  private final static Logger LOG = Logger.getLogger(Scheduler.class);
  private final static Logger AUDIT_LOG = Logging.getAuditLogger(Scheduler.class);

  /** Used to uniquely identify requests arriving at this scheduler. */
  private AtomicInteger counter = new AtomicInteger(0);

  private THostPort address;

  /** Socket addresses for each frontend. */
  HashMap<String, InetSocketAddress> frontendSockets =
      new HashMap<String, InetSocketAddress>();

  /** Thrift client pool for communicating with node monitors */
  ThriftClientPool<InternalService.AsyncClient> nodeMonitorClientPool =
      new ThriftClientPool<InternalService.AsyncClient>(
          new ThriftClientPool.InternalServiceMakerFactory());

  /** Thrift client pool for communicating with front ends. */
  private ThriftClientPool<FrontendService.AsyncClient> frontendClientPool =
      new ThriftClientPool<FrontendService.AsyncClient>(
          new ThriftClientPool.FrontendServiceMakerFactory());

  /** Information about cluster workload due to other schedulers. */
  private SchedulerState state;

  /** Probe ratios to use if the probe ratio is not explicitly set in the request. */
  private double defaultProbeRatioUnconstrained;
  private double defaultProbeRatioConstrained;

  /** A special case scheduling parameter for Spark RDD layouts. */
  private int specialTaskSetSize;

  /**
   * For each request, the task placer that should be used to place the request's tasks. Indexed
   * by the request ID.
   */
  private ConcurrentMap<String, TaskPlacer> requestTaskPlacers;

  private Configuration conf;

  public void initialize(Configuration conf, InetSocketAddress socket) throws IOException {
    address = Network.socketAddressToThrift(socket);
    String mode = conf.getString(SparrowConf.DEPLYOMENT_MODE, "unspecified");
    this.conf = conf;
    if (mode.equals("standalone")) {
      state = new StandaloneSchedulerState();
    } else if (mode.equals("configbased")) {
      state = new ConfigSchedulerState();
    } else if (mode.equals("production")) {
      state = new StateStoreSchedulerState();
    } else {
      throw new RuntimeException("Unsupported deployment mode: " + mode);
    }

    state.initialize(conf);

    defaultProbeRatioUnconstrained = conf.getDouble(SparrowConf.SAMPLE_RATIO,
        SparrowConf.DEFAULT_SAMPLE_RATIO);
    defaultProbeRatioConstrained = conf.getDouble(SparrowConf.SAMPLE_RATIO_CONSTRAINED,
        SparrowConf.DEFAULT_SAMPLE_RATIO_CONSTRAINED);
    specialTaskSetSize = conf.getInt(SparrowConf.SPECIAL_TASK_SET_SIZE,
        SparrowConf.DEFAULT_SPECIAL_TASK_SET_SIZE);

    requestTaskPlacers = Maps.newConcurrentMap();
  }

  public boolean registerFrontend(String appId, String addr) {
    LOG.debug(Logging.functionCall(appId, addr));
    Optional<InetSocketAddress> socketAddress = Serialization.strToSocket(addr);
    if (!socketAddress.isPresent()) {
      LOG.error("Bad address from frontend: " + addr);
      return false;
    }
    frontendSockets.put(appId, socketAddress.get());
    return state.watchApplication(appId);
  }

  /**
   * Callback for enqueueTaskReservations() that does nothing (needed because Thrift can't handle
   * null callbacks).
   */
  private class EnqueueTaskReservationsCallback
  implements AsyncMethodCallback<enqueueTaskReservations_call> {
    String requestId;
    InetSocketAddress nodeMonitorAddress;

    public EnqueueTaskReservationsCallback(String requestId, InetSocketAddress nodeMonitorAddress) {
      this.requestId = requestId;
      this.nodeMonitorAddress = nodeMonitorAddress;
    }

    public void onComplete(enqueueTaskReservations_call response) {
      AUDIT_LOG.debug(Logging.auditEventString(
          "scheduler_complete_enqueue_task", requestId,
          nodeMonitorAddress.getAddress().getHostAddress()));
      try {
        nodeMonitorClientPool.returnClient(nodeMonitorAddress, (AsyncClient) response.getClient());
      } catch (Exception e) {
        LOG.error(e);
      }
      return;
    }

    public void onError(Exception exception) {
      // Do not return error client to pool
      LOG.error(exception);
    }
  }

  /** This is a special case where we want to ensure a very specific scheduling allocation for
   * Spark partitions.*/
  private boolean isSpecialCase(TSchedulingRequest req) {
    if (req.getTasks().size() != specialTaskSetSize) {
      return false;
    }
    for (TTaskSpec t: req.getTasks()) {
      if ((t.getPreference().getNodes() != null)  &&
          (t.getPreference().getNodes().size() != 0)) {
        return false;
      }
    }
    return true;
  }
  /** Handles special case. */
  private void handleSpecialCase(TSchedulingRequest req) throws TException {
    LOG.info("Handling special case request");

    // No tasks have preferences and we have the magic number of tasks
    TSchedulingRequest req1 = new TSchedulingRequest();
    req1.user = req.user;
    req1.app = req.app;
    req1.probeRatio = req.probeRatio;
    TSchedulingRequest req2 = new TSchedulingRequest();
    req2.user = req.user;
    req2.app = req.app;
    req2.probeRatio = req.probeRatio;
    TSchedulingRequest req3 = new TSchedulingRequest();
    req3.user = req.user;
    req3.app = req.app;
    req3.probeRatio = req.probeRatio;

    List<InetSocketAddress> backends = Lists.newArrayList();
    for (InetSocketAddress backend : state.getBackends(req.app).keySet()) {
      backends.add(backend);
    }

    if (!(backends.size() >= (specialTaskSetSize * 3))) {
      LOG.error("Special case expects at least three times as many machines as tasks.");
      return;
    }
    LOG.info(backends);
    for (int i = 0; i < backends.size(); i++) {
      int taskIndex = i / 3;
      if (taskIndex > req.getTasksSize() - 1) { break; }

      TTaskSpec task = req.getTasks().get(i / 3);
      // Create three copies of this task each on a different backend
      TTaskSpec task1 = new TTaskSpec();
      task1.estimatedResources = task.estimatedResources;
      task1.message = task.message;
      task1.taskId = task.taskId;
      task1.preference = new TPlacementPreference();
      LOG.info("Adding: " + i);
      task1.preference.addToNodes(backends.get(i).getHostName());
      req1.addToTasks(task1);
      i++;

      TTaskSpec task2 = new TTaskSpec();
      task2.estimatedResources = task.estimatedResources;
      task2.message = task.message;
      task2.taskId = task.taskId;
      task2.preference = new TPlacementPreference();
      LOG.info("Adding: " + i);
      task2.preference.addToNodes(backends.get(i).getHostName());
      req2.addToTasks(task2);
      i++;

      TTaskSpec task3 = new TTaskSpec();
      task3.estimatedResources = task.estimatedResources;
      task3.message = task.message;
      task3.taskId = task.taskId;
      task3.preference = new TPlacementPreference();
      LOG.info("Adding: " + i);
      task3.preference.addToNodes(backends.get(i).getHostName());
      req3.addToTasks(task3);
    }
    LOG.info("Three requests: " + req1 + req1 + req3);
    submitJob(req1);
    submitJob(req2);
    submitJob(req3);
  }

  public void submitJob(TSchedulingRequest request) throws TException {
    if (isSpecialCase(request)) {
      handleSpecialCase(request);
      return;
    }
    LOG.debug(Logging.functionCall(request));
    long start = System.currentTimeMillis();

    String requestId = getRequestId();

    // Logging the address here is somewhat redundant, since all of the
    // messages in this particular log file come from the same address.
    // However, it simplifies the process of aggregating the logs, and will
    // also be useful when we support multiple daemons running on a single
    // machine.
    AUDIT_LOG.info(Logging.auditEventString("arrived", requestId,
        request.getTasks().size(),
        address.getHost(), address.getPort()));

    String app = request.getApp();
    List<TTaskSpec> tasks = request.getTasks();
    Set<InetSocketAddress> backends = state.getBackends(app).keySet();
    boolean constrained = false;
    for (TTaskSpec task : tasks) {
      constrained = constrained || (
          task.preference != null &&
          task.preference.nodes != null &&
          !task.preference.nodes.isEmpty());
    }

    TaskPlacer taskPlacer;
    if (constrained) {
      if (request.isSetProbeRatio()) {
        taskPlacer = new ConstrainedTaskPlacer(requestId, request.getProbeRatio());
      } else {
        taskPlacer = new ConstrainedTaskPlacer(requestId, defaultProbeRatioConstrained);
      }
    } else {
      if (request.isSetProbeRatio()) {
        taskPlacer = new UnconstrainedTaskPlacer(requestId, request.getProbeRatio());
      } else {
        taskPlacer = new UnconstrainedTaskPlacer(requestId, defaultProbeRatioUnconstrained);
      }
    }
    requestTaskPlacers.put(requestId, taskPlacer);

    Map<InetSocketAddress, TEnqueueTaskReservationsRequest> enqueueTaskReservationsRequests;
    enqueueTaskReservationsRequests = taskPlacer.getEnqueueTaskReservationsRequests(
        request, requestId, backends, address);

    // Request to enqueue a task at each of the selected nodes.
    for (Entry<InetSocketAddress, TEnqueueTaskReservationsRequest> entry :
      enqueueTaskReservationsRequests.entrySet())  {
      try {
        InternalService.AsyncClient client = nodeMonitorClientPool.borrowClient(entry.getKey());
        LOG.debug("Launching enqueueTask for request " + requestId + "on node: " + entry.getKey());
        // Pass in null callback because the RPC doesn't return anything.
        AUDIT_LOG.debug(Logging.auditEventString(
            "scheduler_launch_enqueue_task", entry.getValue().requestId,
            entry.getKey().getAddress().getHostAddress()));
        client.enqueueTaskReservations(
            entry.getValue(), new EnqueueTaskReservationsCallback(requestId, entry.getKey()));
      } catch (Exception e) {
        LOG.error(e);
      }
    }

    long end = System.currentTimeMillis();
    LOG.debug("All tasks enqueued for request " + requestId + "; returning. Total time: " +
              (end - start) + " milliseconds");
  }

  public synchronized List<TTaskLaunchSpec> getTask(
      String requestId, THostPort nodeMonitorAddress) {
    LOG.debug(Logging.functionCall(requestId, nodeMonitorAddress));
    if (!requestTaskPlacers.containsKey(requestId)) {
      LOG.error("Received getTask() request for request " + requestId + " which had no more " +
          "pending reservations");
      return Lists.newArrayList();
    }
    TaskPlacer taskPlacer = requestTaskPlacers.get(requestId);
    List<TTaskLaunchSpec> taskLaunchSpecs = taskPlacer.assignTask(nodeMonitorAddress);
    if (taskLaunchSpecs == null || taskLaunchSpecs.size() > 1) {
      LOG.error("Received invalid task placement for request " + requestId + ": " +
                taskLaunchSpecs.toString());
      return Lists.newArrayList();
    } else if (taskLaunchSpecs.size() == 1) {
      AUDIT_LOG.info(Logging.auditEventString("scheduler_assigned_task", requestId,
          taskLaunchSpecs.get(0).taskId,
          nodeMonitorAddress.getHost()));
    } else {
      AUDIT_LOG.info(Logging.auditEventString("scheduler_get_task_no_task", requestId));
    }
    if (taskPlacer.allResponsesReceived()) {
      LOG.debug("All responses received for request " + requestId);
      // Remove the entry in requestTaskPlacers once all tasks have been placed, so that
      // requestTaskPlacers doesn't grow to be unbounded.
      requestTaskPlacers.remove(requestId);
    }
    return taskLaunchSpecs;
  }

  /**
   * Returns an ID that identifies a request uniquely (across all Sparrow schedulers).
   *
   * This should only be called once for each request (it will return a different
   * identifier if called a second time).
   *
   * TODO: Include the port number, so this works when there are multiple schedulers
   * running on a single machine (as there will be when we do large scale testing).
   */
  private String getRequestId() {
    /* The request id is a string that includes the IP address of this scheduler followed
     * by the counter.  We use a counter rather than a hash of the request because there
     * may be multiple requests to run an identical job. */
    return String.format("%s_%d", Network.getIPAddress(conf), counter.getAndIncrement());
  }

  private class sendFrontendMessageCallback implements
  AsyncMethodCallback<frontendMessage_call> {
    private InetSocketAddress frontendSocket;
    private FrontendService.AsyncClient client;
    public sendFrontendMessageCallback(InetSocketAddress socket, FrontendService.AsyncClient client) {
      frontendSocket = socket;
      this.client = client;
    }

    public void onComplete(frontendMessage_call response) {
      try { frontendClientPool.returnClient(frontendSocket, client); }
      catch (Exception e) { LOG.error(e); }
    }

    public void onError(Exception exception) {
      // Do not return error client to pool
      LOG.error(exception);
    }
  }

  public void sendFrontendMessage(String app, TFullTaskId taskId,
      int status, ByteBuffer message) {
    LOG.debug(Logging.functionCall(app, taskId, message));
    InetSocketAddress frontend = frontendSockets.get(app);
    if (frontend == null) {
      LOG.error("Requested message sent to unregistered app: " + app);
    }
    try {
      FrontendService.AsyncClient client = frontendClientPool.borrowClient(frontend);
      client.frontendMessage(taskId, status, message,
          new sendFrontendMessageCallback(frontend, client));
    } catch (IOException e) {
      LOG.error("Error launching message on frontend: " + app, e);
    } catch (TException e) {
      LOG.error("Error launching message on frontend: " + app, e);
    } catch (Exception e) {
      LOG.error("Error launching message on frontend: " + app, e);
    }
  }
}
