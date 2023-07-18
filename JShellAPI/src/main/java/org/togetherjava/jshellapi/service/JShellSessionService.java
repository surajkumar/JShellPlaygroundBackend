package org.togetherjava.jshellapi.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.togetherjava.jshellapi.Config;
import org.togetherjava.jshellapi.exceptions.DockerException;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class JShellSessionService {
    private Config config;
    private ScheduledExecutorService scheduler;
    private final Map<String, JShellService> jshellSessions = new HashMap<>();
    private void initScheduler() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            List<String> toDie = jshellSessions.keySet()
                    .stream()
                    .filter(id -> jshellSessions.get(id).shouldDie())
                    .toList();
            for(String id : toDie) {
                try {
                    deleteSession(id);
                } catch (DockerException ex) {
                    ex.printStackTrace();
                }
            }
        }, config.schedulerSessionKillScanRateSeconds(), config.schedulerSessionKillScanRateSeconds(), TimeUnit.SECONDS);
    }
    void notifyDeath(String id) {
        JShellService shellService = jshellSessions.get(id);
        if(!shellService.isClosed()) {
            throw new RuntimeException("JShell Service isn't dead when it should for id " + id);
        }
        jshellSessions.remove(id);
    }

    public boolean hasSession(String id) {
        return jshellSessions.containsKey(id);
    }

    public JShellService session(String id) throws DockerException {
        if(!hasSession(id)) {
            return createSession(id, config.regularSessionTimeoutSeconds(), true, config.evalTimeoutSeconds());
        }
        return jshellSessions.get(id);
    }
    public JShellService session() throws DockerException {
        return createSession(UUID.randomUUID().toString(), config.regularSessionTimeoutSeconds(), false, config.evalTimeoutSeconds());
    }
    public JShellService oneTimeSession() throws DockerException {
        return createSession(UUID.randomUUID().toString(), config.oneTimeSessionTimeoutSeconds(), false, config.evalTimeoutSeconds());
    }

    public void deleteSession(String id) throws DockerException {
        JShellService service = jshellSessions.remove(id);
        service.stop();
        scheduler.schedule(service::close, 500, TimeUnit.MILLISECONDS);
    }

    private synchronized JShellService createSession(String id, long sessionTimeout, boolean renewable, long evalTimeout) throws DockerException {
        if(hasSession(id)) {    //Just in case race condition happens just before createSession
            return jshellSessions.get(id);
        }
        if(jshellSessions.size() >= config.maxAliveSessions()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many sessions, try again later :(.");
        }
        JShellService service = new JShellService(this, id, sessionTimeout, renewable, evalTimeout, config.dockerMaxRamMegaBytes(), config.dockerCPUsUsage());
        jshellSessions.put(id, service);
        return service;
    }

    @Autowired
    public void setConfig(Config config) {
        this.config = config;
        initScheduler();
    }
}
