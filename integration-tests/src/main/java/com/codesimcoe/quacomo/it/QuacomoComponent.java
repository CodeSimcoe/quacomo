package com.codesimcoe.quacomo.it;

import com.codesimcoe.quacomo.runtime.Component;
import com.codesimcoe.quacomo.runtime.ProducedComponentEvents;

@Component
public class QuacomoComponent implements SampleService {

  @ProducedComponentEvents
  SampleEvents sampleEvents;

  @Override
  public void sayHello() {
    IO.println("Hello");
    sampleEvents.onSampleEvent();
  }
}
