package com.thoughtworks.lean.rancher;

import com.google.common.base.Preconditions;
import io.rancher.Rancher;
import io.rancher.base.Filters;
import io.rancher.service.EnvironmentService;
import io.rancher.service.ProjectService;
import io.rancher.service.ServiceService;
import io.rancher.type.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.TimeoutException;

@SpringBootApplication
public class RancherServiceUpgrader implements CommandLineRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(RancherServiceUpgrader.class);


  // Simple example shows how a command line spring application can execute an
  // injected bean service. Also demonstrates how you can use @Value to inject
  // command line args ('--name=whatever') or application properties
  @Value("${rancher.uri}")
  private String rancherUri;

  @Value("${app.secret}")
  private String appSecret;

  @Value("${secret.key}")
  private String secretKey;

  @Value("${service.name}")
  private String serviceName;

  @Value("${stack.name}")
  private String stackName;

  @Value("${environment.name}")
  private String environmentName;

  @Value("${upgrade.timeout:600000}")
  private long upgradeTimeout = 600 * 1000;

  @Value("${rollback.on.fail:false}")
  private boolean rollBackOnFail = true;

  @Value("${status.check.interval:5000}")
  private long statusCheckInterval = 5000;

  Rancher rancher;


  @Override
  public void run(String... args) throws IOException, TimeoutException, InterruptedException {

    Preconditions.checkNotNull(rancherUri);
    Preconditions.checkNotNull(appSecret);
    Preconditions.checkNotNull(secretKey);
    Preconditions.checkNotNull(environmentName);
    Preconditions.checkNotNull(stackName);
    Preconditions.checkNotNull(serviceName);

    // remote
    // Rancher.Config config = new Rancher.Config(new URL("http://rancher-server:8080/v1/"), "14E81ABF45359F074521", "E7xmHz2h22iJWUpf1atDEnqsd2zzv9udHdsQkjjy");

    // local
    Rancher.Config config = new Rancher.Config(new URL(rancherUri), appSecret, secretKey);
    rancher = new Rancher(config);

    Project environment = getEnvironment(environmentName);
    Environment stack = getStack(stackName, environment.getId());
    Service service = getService(environment.getId(), stack.getId());

    LOGGER.info("Get service succeed ! serviceName: " + service.getName() + " serviceId: " + service.getId());
    //System.out.println(project.getName());
    if (!service.getState().equals("active")) {
      String stateErrorInfo = "Service " + service.getName() + "(" + service.getId() + ") is not active State!!!";
      throw new IllegalStateException(stateErrorInfo);
    }
    ServiceUpgrade serviceUpgrade = new ServiceUpgrade();
    //
    InServiceUpgradeStrategy inServiceUpgradeStrategy = new InServiceUpgradeStrategy();
    inServiceUpgradeStrategy.setBatchSize(1);
    inServiceUpgradeStrategy.setIntervalMillis(2000);
    inServiceUpgradeStrategy.setStartFirst(false);
    inServiceUpgradeStrategy.setLaunchConfig(service.getLaunchConfig());
    serviceUpgrade.setInServiceStrategy(inServiceUpgradeStrategy);

    ServiceService serviceService = rancher.type(ServiceService.class);
    serviceService.upgrade(service.getId(), serviceUpgrade).execute().body();
    long upgradeTime = 0;
    while (upgradeTime < upgradeTimeout) {
      Service serviceStatus = serviceService.get(service.getId()).execute().body();

      Thread.sleep(statusCheckInterval);
      upgradeTime += statusCheckInterval;
      if (upgradeTime % (statusCheckInterval * 4) == 0) {
        LOGGER.info("Service " + service.getName() + "(" + service.getId() + ") upgrading.......");
      }
      if (serviceStatus.getState().equals("upgraded") && serviceStatus.getHealthState().equals("healthy")) {
        serviceService.finishupgrade(service.getId()).execute();
        LOGGER.info("Service " + service.getName() + "(" + service.getId() + ") upgrade [[[Succeed]]] !");
        return;
      }
    }
    if (rollBackOnFail) {
      serviceService.cancelrollback(service.getId());
    }
    String failInfo = "Service " + service.getName() + "(" + service.getId() + ") upgrade [[[Failed]]] on time out!";
    LOGGER.error(failInfo);
    throw new TimeoutException(failInfo);
  }

  private Environment getStack(String stackName, String environmentId) throws IOException {
    EnvironmentService environmentService = rancher.type(EnvironmentService.class);
    Filters envFilter = new Filters();
    envFilter.put("name", stackName);
    envFilter.put("accountId", environmentId);
    return environmentService.list(envFilter).execute().body().getData().stream().findFirst().get();
  }

  private Service getService(String enviromentId, String stackId) throws IOException {
    ServiceService serviceService = rancher.type(ServiceService.class);
    Filters filters = new Filters();
    filters.put("name", serviceName);
    filters.put("accountId", enviromentId);
    filters.put("environmentId", stackId);
    return serviceService.list(filters).execute().body().getData().stream().findFirst().get();
  }

  private Project getEnvironment(String projectName) throws IOException {
    ProjectService projectService = rancher.type(ProjectService.class);
    return projectService.list().execute().body().getData().stream().filter(e -> e.getName().equals(projectName)).findFirst().get();
  }

  public static void main(String[] args) throws Exception {
    SpringApplication.run(RancherServiceUpgrader.class, args);
  }

}
