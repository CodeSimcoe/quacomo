package com.codesimcoe.quacomo.deployment;

import java.lang.reflect.Modifier;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import jakarta.enterprise.context.NormalScope;
import jakarta.inject.Scope;
import org.eclipse.microprofile.context.ManagedExecutor;
import com.codesimcoe.quacomo.runtime.AsyncComponentEvents;
import io.quarkus.gizmo.BytecodeCreator;
import io.quarkus.gizmo.FieldDescriptor;
import io.quarkus.gizmo.FunctionCreator;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTransformation;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import com.codesimcoe.quacomo.runtime.Component;
import com.codesimcoe.quacomo.runtime.ComponentEvents;
import com.codesimcoe.quacomo.runtime.ComponentInstances;
import com.codesimcoe.quacomo.runtime.ComponentServices;
import com.codesimcoe.quacomo.runtime.ProducedComponentEvents;
import com.codesimcoe.quacomo.runtime.UsedComponentServices;
import io.quarkus.arc.deployment.AnnotationsTransformerBuildItem;
import io.quarkus.arc.deployment.BeanDefiningAnnotationBuildItem;
import io.quarkus.arc.deployment.GeneratedBeanBuildItem;
import io.quarkus.arc.deployment.GeneratedBeanGizmoAdaptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;

class QuacomoProcessor {
  private static final DotName COMPONENT = DotName.createSimple(Component.class);
  private static final DotName SERVICES = DotName.createSimple(ComponentServices.class);
  private static final DotName EVENTS = DotName.createSimple(ComponentEvents.class);
  private static final DotName USED = DotName.createSimple(UsedComponentServices.class);
  private static final DotName PRODUCED = DotName.createSimple(ProducedComponentEvents.class);

  @BuildStep
  FeatureBuildItem feature() {
    return new FeatureBuildItem("quacomo");
  }

  @BuildStep
  BeanDefiningAnnotationBuildItem components() {
    return new BeanDefiningAnnotationBuildItem(COMPONENT, DotName.createSimple(Singleton.class), false);
  }

  @BuildStep
  AnnotationsTransformerBuildItem injection() {
    return new AnnotationsTransformerBuildItem(AnnotationTransformation
      .forFields()
      .when(field -> field.hasAnnotation(USED) || field.hasAnnotation(PRODUCED))
      .transform(context -> {
        context.add(Inject.class);
        FieldInfo field = context.declaration().asField();
        AnnotationInstance binding = field.annotation(USED);
        if (binding == null) {
          binding = field.annotation(PRODUCED);
        }
        if (field.hasAnnotation(PRODUCED)
            || (binding.value("remote") != null && !binding.value("remote").asString().isEmpty())) {
          context.add(AnnotationInstance
            .builder(com.codesimcoe.quacomo.runtime.RemoteBinding.class)
            .value(binding.value("remote") == null ? "" : binding.value("remote").asString())
            .build()
          );
        }
      })
    );
  }

  @BuildStep
  void generate(CombinedIndexBuildItem combined, BuildProducer<GeneratedBeanBuildItem> generated) {
    IndexView index = combined.getIndex();
    for (DotName marker : List.of(SERVICES, EVENTS)) {
      for (AnnotationInstance annotation : index.getAnnotations(marker)) {
        ClassInfo contract = annotation.target().asClass();
        require(
            contract.isInterface() && Modifier.isPublic(contract.flags()),
            "Contract must be a public interface: " + contract.name()
        );
        require(contract.typeParameters().isEmpty(), "Generic contracts are not supported: " + contract.name());
      }
    }
    for (AnnotationInstance annotation : index.getAnnotations(COMPONENT)) {
      ClassInfo component = annotation.target().asClass();
      require(
          !component.isInterface() && !Modifier.isAbstract(component.flags()),
          "Component must be concrete: " + component.name()
      );
      for (AnnotationInstance scope : component.declaredAnnotations()) {
        ClassInfo annotationType = index.getClassByName(scope.name());
        boolean otherScope = annotationType != null
            && !scope.name().equals(DotName.createSimple(Singleton.class))
            && (annotationType.hasAnnotation(DotName.createSimple(NormalScope.class))
            || annotationType.hasAnnotation(DotName.createSimple(Scope.class)));
        require(!otherScope, "Components must use singleton scope: " + component.name());
      }
    }
    for (DotName marker : List.of(USED, PRODUCED)) {
      for (AnnotationInstance annotation : index.getAnnotations(marker)) {
        FieldInfo field = annotation.target().asField();
        require(
            !Modifier.isStatic(field.flags()) && !Modifier.isFinal(field.flags()),
            "Injection field must not be static or final: " + field
        );
        require(
            !(field.hasAnnotation(USED) && field.hasAnnotation(PRODUCED)),
            "Conflicting injection annotations: " + field
        );
        ClassInfo contract = index.getClassByName(field.type().name());
        require(
            contract != null && contract.hasAnnotation(marker.equals(USED) ? SERVICES : EVENTS),
            "Missing contract annotation for " + field
        );
        boolean remote = annotation.value("remote") != null && !annotation.value("remote").asString().isEmpty();
        if (remote) {
          require(
              contract.hasAnnotation(DotName.createSimple(com.codesimcoe.quacomo.runtime.Remotable.class)),
              "Remote binding requires @Remotable: " + field
          );
        }
        if (marker.equals(USED) && !remote) {
          require(
              components(index, contract).size() == 1,
              "Expected exactly one component providing " + contract.name() + " for " + field
          );
        }
      }
    }
    for (AnnotationInstance annotation : index.getAnnotations(EVENTS)) {
      ClassInfo contract = annotation.target().asClass();
      List<ClassInfo> subscribers = components(index, contract);
      try (ClassCreator creator = ClassCreator
        .builder()
        .classOutput(new GeneratedBeanGizmoAdaptor(generated))
        .className(contract.name() + "_JaCoMoDispatcher")
        .interfaces(contract.name().toString())
        .build()) {
        creator.addAnnotation(Singleton.class);
        creator.addAnnotation(ProducedComponentEvents.class);
        creator.addAnnotation(com.codesimcoe.quacomo.runtime.RemoteBinding.class).addValue("value", "");
        Map<String, MethodInfo> methods = new LinkedHashMap<>();
        collectMethods(index, contract, methods);
        FieldDescriptor executor = null;
        for (MethodInfo method : methods.values()) {
          boolean async = isAsync(method.returnType());
          if (async && !subscribers.isEmpty() && executor == null) {
            var field = creator.getFieldCreator("executor", ManagedExecutor.class).setModifiers(0);
            field.addAnnotation(Inject.class);
            executor = field.getFieldDescriptor();
          }
          require(
              method.returnType().kind() == Type.Kind.VOID || async,
              "Event methods must return void or CompletionStage<Void>: " + method
          );
          String[] parameters = MethodDescriptor.of(method).getParameterTypes();
          MethodCreator body =
              creator.getMethodCreator(method.name(), MethodDescriptor.of(method).getReturnType(), parameters);
          ResultHandle[] arguments = new ResultHandle[parameters.length];
          for (int i = 0; i < arguments.length; i++) {
            arguments[i] = body.getMethodParam(i);
          }
          if (async) {
            ResultHandle deliveries = body.newArray(CompletableFuture.class, subscribers.size());
            int position = 0;
            for (ClassInfo subscriber : subscribers) {
              FunctionCreator task = body.createFunction(Supplier.class);
              BytecodeCreator invocation = task.getBytecode();
              invocation.returnValue(invokeSubscriber(invocation, contract, method, subscriber, arguments));
              ResultHandle delivery = body.invokeStaticMethod(
                  MethodDescriptor.ofMethod(
                      AsyncComponentEvents.class,
                      "submit",
                      CompletableFuture.class,
                      ManagedExecutor.class,
                      Supplier.class
                  ),
                  body.readInstanceField(executor, body.getThis()),
                  task.getInstance()
              );
              body.writeArrayValue(deliveries, position++, delivery);
            }
            body.returnValue(body.invokeStaticMethod(
                MethodDescriptor.ofMethod(
                    AsyncComponentEvents.class,
                    "all",
                    CompletionStage.class,
                    CompletableFuture[].class
                ),
                deliveries
            ));
          } else {
            for (ClassInfo subscriber : subscribers) {
              invokeSubscriber(body, contract, method, subscriber, arguments);
            }
            body.returnVoid();
          }
        }
      }
    }
  }

  private static boolean isAsync(Type type) {
    return type.kind() == Type.Kind.PARAMETERIZED_TYPE
        && type.name().equals(DotName.createSimple(CompletionStage.class))
        && type.asParameterizedType().arguments().size() == 1
        && type.asParameterizedType().arguments().getFirst().equals(Type.create(Void.class));
  }

  private static ResultHandle invokeSubscriber(
      BytecodeCreator body,
      ClassInfo contract,
      MethodInfo method,
      ClassInfo subscriber,
      ResultHandle[] arguments
  ) {
    ResultHandle instance = body.invokeStaticMethod(
        MethodDescriptor.ofMethod(ComponentInstances.class, "get", Object.class, Class.class),
        body.loadClass(subscriber.name().toString())
    );
    return body.invokeInterfaceMethod(
        MethodDescriptor.ofMethod(
            contract.name().toString(),
            method.name(),
            MethodDescriptor.of(method).getReturnType(),
            MethodDescriptor.of(method).getParameterTypes()
        ),
        body.checkCast(instance, contract.name().toString()),
        arguments
    );
  }

  private static List<ClassInfo> components(IndexView index, ClassInfo contract) {
    return index
      .getAllKnownImplementations(contract.name())
      .stream()
      .filter(c -> c.hasAnnotation(COMPONENT) && !Modifier.isAbstract(c.flags()))
      .sorted(Comparator.comparing(c -> c.name().toString()))
      .toList();
  }

  private static void collectMethods(IndexView index, ClassInfo contract, Map<String, MethodInfo> methods) {
    for (Type parent : contract.interfaceTypes()) {
      require(parent.kind() == Type.Kind.CLASS, "Generic event inheritance is not supported: " + contract.name());
      ClassInfo info = index.getClassByName(parent.name());
      require(info != null, "Event parent is not indexed: " + parent.name());
      collectMethods(index, info, methods);
    }
    for (MethodInfo method : contract.methods()) {
      if (Modifier.isStatic(method.flags()) || Modifier.isPrivate(method.flags()) || method.name().startsWith("<")) {
        continue;
      }
      require(method.typeParameters().isEmpty(), "Generic event methods are not supported: " + method);
      methods.put(method.name() + method.parameterTypes(), method);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException("JaCoMo: " + message);
    }
  }
}
