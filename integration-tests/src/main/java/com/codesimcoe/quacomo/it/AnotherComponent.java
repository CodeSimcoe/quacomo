package com.codesimcoe.quacomo.it;

import com.codesimcoe.quacomo.runtime.Component;
import com.codesimcoe.quacomo.runtime.UsedComponentServices;
import io.quarkus.runtime.Startup;

@Component
public class AnotherComponent implements SampleEvents {

  @UsedComponentServices
  SampleService sampleService;

  @Override
  public void onSampleEvent() {
    IO.println("Event triggered");
  }

  @Startup
  void startup() {
    sampleService.sayHello();
  }
}
