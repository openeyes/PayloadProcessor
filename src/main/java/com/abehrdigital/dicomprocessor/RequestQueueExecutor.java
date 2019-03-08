/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.abehrdigital.dicomprocessor;

import com.abehrdigital.dicomprocessor.dao.RequestQueueDaoManager;
import com.abehrdigital.dicomprocessor.exceptions.RequestQueueMissingException;
import com.abehrdigital.dicomprocessor.models.RequestQueue;
import com.abehrdigital.dicomprocessor.models.RequestRoutine;
import com.abehrdigital.dicomprocessor.utils.DaoFactory;
import org.hibernate.LockMode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author admin
 */
public class RequestQueueExecutor implements RequestThreadListener {

    private String requestQueueName;
    private RequestQueueLocker requestQueueLocker;
    private RequestQueueDaoManager daoManager;
    private final Map<Integer, Runnable> requestIdToThreadSyncMap;
    private RequestQueue currentRequestQueue;
    private int currentActiveThreads;
    private int currentRequestId;
    private final static int LOCK_MAXIMUM_TRY_COUNT = 20;
    private RoutineLibrarySynchronizer routineLibrarySynchronizer;

    public RequestQueueExecutor(String requestQueueName, RoutineLibrarySynchronizer routineLibrarySynchronizer) {
        this.requestQueueName = requestQueueName;
        daoManager = DaoFactory.createRequestQueueExecutorDaoManager();
        requestQueueLocker = new RequestQueueLocker(requestQueueName);
        requestIdToThreadSyncMap = new HashMap<>();
        this.routineLibrarySynchronizer = routineLibrarySynchronizer;
    }

    public void execute() throws RequestQueueMissingException {
        try {
            requestQueueLocker.lockWithMaximumTryCount(LOCK_MAXIMUM_TRY_COUNT);
            List<RequestRoutine> requestRoutinesForExecution = daoManager
                    .getRequestRoutineDao()
                    .getRoutinesForQueueProcessing(requestQueueName);

            for (RequestRoutine routineForExecution : requestRoutinesForExecution) {
                currentRequestQueue = getUpToDateRequestQueue();
                currentRequestId = routineForExecution.getRequestId();
                boolean requestIsInActiveThreadMap;

                synchronized (requestIdToThreadSyncMap) {
                    currentActiveThreads = requestIdToThreadSyncMap.size();
                    requestIsInActiveThreadMap = requestIdToThreadSyncMap.containsKey(currentRequestId);
                }

                if (currentActiveThreads >= currentRequestQueue.getMaximumActiveThreads()) {
                    break;
                }

                if (!requestIsInActiveThreadMap) {
                    saveAndStartRequestWorker(createRequestWorkerWithCurrentRequestId());
                    saveWithLock(false , 0 , 0);
                }
            }

            currentRequestQueue = getUpToDateRequestQueue();
            if (requestRoutinesForExecution.size() != 0) {
                TimeUnit.MILLISECONDS.sleep(currentRequestQueue.getBusyYieldMs());
            } else {
                routineLibrarySynchronizer.sync();
                TimeUnit.MILLISECONDS.sleep(currentRequestQueue.getIdleYieldMs());
            }
        } catch (RequestQueueMissingException queueMissingException){
            throw queueMissingException;
        } catch(Exception exception) {
            daoManager.rollback();
            Logger.getLogger(RequestQueueExecutor.class.getName()).log(Level.SEVERE,
                    exception.toString() + " Executor exception");
            exception.printStackTrace();
        }
    }

    private synchronized void saveWithLock(boolean dequeue , int successfulRoutineCount, int failedRoutineCount) {
        currentRequestQueue = getUpToDateRequestQueueForUpdate();
        setLastThreadSpawnDateAndRequestId();
        if(dequeue){
            currentRequestQueue = getUpToDateRequestQueueForUpdate();
            setActiveThreadAndExecutionCounts(successfulRoutineCount, failedRoutineCount);
            daoManager.getRequestQueueDao().update(currentRequestQueue);
            daoManager.commit();
        } else {
            setLastThreadSpawnDateAndRequestId();
            currentRequestQueue.setTotalActiveThreadCount(currentActiveThreads);
        }
        daoManager.getRequestQueueDao().update(currentRequestQueue);
        daoManager.commit();
    }

    private RequestQueue getUpToDateRequestQueue() {
        daoManager.clearSession();
        return daoManager.getRequestQueueDao().get(requestQueueName);
    }

    private void setLastThreadSpawnDateAndRequestId() {
        currentRequestQueue.setLastThreadSpawnRequestId(currentRequestId);
        currentRequestQueue.setLastThreadSpawnDateToCurrentTimestamp();
    }

    private RequestQueue getUpToDateRequestQueueForUpdate() {
        daoManager.transactionStart();
        return daoManager.getRequestQueueDao().getWithLock(requestQueueName, LockMode.UPGRADE_NOWAIT);
    }

    private Thread createRequestWorkerWithCurrentRequestId() {
        return new Thread(
                new RequestWorker(currentRequestId, requestQueueName, this),
                "request_id=" + currentRequestId + " worker thread"
        );
    }

    private void saveAndStartRequestWorker(Thread requestWorker) {
        synchronized (requestIdToThreadSyncMap) {
            requestIdToThreadSyncMap.put(currentRequestId, requestWorker);
            currentActiveThreads = requestIdToThreadSyncMap.size();
        }
        requestWorker.start();
    }

    @Override
    public synchronized void deQueue(int requestId, int successfulRoutineCount, int failedRoutineCount) {
        synchronized (requestIdToThreadSyncMap) {
            requestIdToThreadSyncMap.remove(requestId);
            currentActiveThreads = requestIdToThreadSyncMap.size();
        }

        saveWithLock(true , successfulRoutineCount , failedRoutineCount);
    }

    private void setActiveThreadAndExecutionCounts(int successfulRoutineCount, int failedRoutineCount) {
        currentRequestQueue.setTotalActiveThreadCount(currentActiveThreads);
        currentRequestQueue.incrementSuccessCount(successfulRoutineCount);
        currentRequestQueue.incrementFailCount(failedRoutineCount);
        currentRequestQueue.updateTotalExecuteCount();
    }

    public void shutDown() {
        requestQueueLocker.unlock();
        daoManager.shutDown();
    }
}