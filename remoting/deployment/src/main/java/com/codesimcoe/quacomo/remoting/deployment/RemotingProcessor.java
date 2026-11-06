package com.codesimcoe.quacomo.remoting.deployment;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.CompletionStage;
import jakarta.inject.*;
import org.jboss.jandex.*;
import org.jboss.jandex.Type;
import com.fasterxml.jackson.databind.JsonNode;
import com.codesimcoe.quacomo.runtime.*;
import com.codesimcoe.quacomo.remoting.*;
import io.quarkus.arc.deployment.*;
import io.quarkus.deployment.annotations.*;
import io.quarkus.deployment.builditem.*;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.gizmo.*;

class RemotingProcessor {
  private static final DotName REMOTABLE = DotName.createSimple(Remotable.class);
  private static final DotName SERVICES = DotName.createSimple(ComponentServices.class);
  private static final DotName EVENTS = DotName.createSimple(ComponentEvents.class);
  private static final DotName COMPONENT = DotName.createSimple(Component.class);

  @BuildStep
  FeatureBuildItem feature() {
    return new FeatureBuildItem("quacomo-remoting");
  }

  @BuildStep
  AdditionalIndexedClassesBuildItem indexEndpoint() {
    return new AdditionalIndexedClassesBuildItem(RemoteEndpoint.class.getName());
  }

  @BuildStep
  AdditionalBeanBuildItem beans() {
    return AdditionalBeanBuildItem
      .builder()
      .addBeanClasses(RemoteEndpoint.class, RemoteTransport.class)
      .setUnremovable()
      .build();
  }

  @BuildStep
  void generate(
      CombinedIndexBuildItem combined,
      BuildProducer<GeneratedBeanBuildItem> beans,
      BuildProducer<ReflectiveClassBuildItem> reflection,
      BuildProducer<GeneratedResourceBuildItem> resources
  ) {
    IndexView index = combined.getIndex();
    Set<String> dtos = new HashSet<>();
    Set<String> manifest = new TreeSet<>();
    for (AnnotationInstance annotation : index.getAnnotations(REMOTABLE)) {
      ClassInfo contract = annotation.target().asClass();
      boolean event = contract.hasAnnotation(EVENTS);
      require(
              contract.isInterface()
          && Modifier.isPublic(contract.flags())
          && contract.typeParameters().isEmpty()
          && (event ^ contract.hasAnnotation(SERVICES)),
          "@Remotable requires a public service or event interface: " + contract.name()
      );
      Map<String, MethodInfo> methods = new TreeMap<>();
      collect(index, contract, methods);
      for (MethodInfo method : methods.values()) {
        manifest.add(key(contract, method));
        require(
                method.typeParameters().isEmpty()
            && method.returnType().kind() == Type.Kind.PARAMETERIZED_TYPE
            && method.returnType().name().toString().equals(CompletionStage.class.getName()),
            "Remote methods must return CompletionStage<T>: " + method
        );
        Type result = method.returnType().asParameterizedType().arguments().getFirst();
        require(
            !event || result.equals(Type.create(Void.class)),
            "Remote events must return CompletionStage<Void>: " + method
        );
        validate(index, result, dtos, new HashSet<>());
        for (Type parameter : method.parameterTypes()) {
          validate(index, parameter, dtos, new HashSet<>());
        }
      }
      List<ClassInfo> providers = index
        .getAllKnownImplementations(contract.name())
        .stream()
        .filter(c -> c.hasAnnotation(COMPONENT) && !Modifier.isAbstract(c.flags()))
        .toList();
      require(event || providers.size() <= 1, "Multiple local providers for remotable service: " + contract.name());
      if (event || !providers.isEmpty()) {
        int number = 0;
        for (MethodInfo method : methods.values()) {
          export(
              beans,
              contract,
              method,
              event ? contract.name() + "_JaCoMoDispatcher" : providers.getFirst().name().toString(),
              number++
          );
        }
      }
      Set<String> bindings = new TreeSet<>();
      for (AnnotationInstance binding :
          index.getAnnotations(DotName.createSimple(event ? ProducedComponentEvents.class : UsedComponentServices.class)
      )) {
        if (binding.target().kind() != AnnotationTarget.Kind.FIELD
            || !binding.target().asField().type().name().equals(contract.name())) {
          continue;
        }
        AnnotationValue remote = binding.value("remote");
        if (remote == null || remote.asString().isEmpty()) {
          continue;
        }
        String destination = remote.asString();
        require(event || !destination.contains(","), "A remote service requires one destination: " + destination);
        for (String name : destination.split(",", -1)) {
          require(name.trim().matches("[A-Za-z0-9_-]+"), "Invalid remote destination: " + destination);
        }
        bindings.add(destination);
      }
      int number = 0;
      for (String binding : bindings) {
        client(beans, contract, methods.values(), binding, event, number++);
      }
    }
    resources.produce(
        new GeneratedResourceBuildItem(
            "META-INF/quacomo/remoting-operations.txt",
            String.join("\n", manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
    );
    if (!dtos.isEmpty()) {
      reflection.produce(ReflectiveClassBuildItem
        .builder(dtos.toArray(String[]::new))
        .constructors()
        .methods()
        .fields()
        .build()
      );
    }
    reflection.produce(ReflectiveClassBuildItem
      .builder(RemoteProtocol.Request.class, RemoteProtocol.Reply.class)
      .constructors()
      .methods()
      .fields()
      .build()
    );
  }

  private static void client(
      BuildProducer<GeneratedBeanBuildItem> beans,
      ClassInfo contract,
      Collection<MethodInfo> methods,
      String binding,
      boolean event,
      int number
  ) {
    try (ClassCreator cc = ClassCreator
      .builder()
      .classOutput(new GeneratedBeanGizmoAdaptor(beans))
      .className(contract.name() + "_RemoteClient" + number)
      .interfaces(contract.name().toString())
      .build()) {
      cc.addAnnotation(Singleton.class);
      cc.addAnnotation(RemoteBinding.class).addValue("value", binding);
      if (event) {
        cc.addAnnotation(ProducedComponentEvents.class);
      }
      var transport = cc.getFieldCreator("transport", RemoteTransport.class).setModifiers(0);
      transport.addAnnotation(Inject.class);
      for (MethodInfo method : methods) {
        MethodDescriptor descriptor = MethodDescriptor.of(method);
        MethodCreator body =
            cc.getMethodCreator(method.name(), descriptor.getReturnType(), descriptor.getParameterTypes());
        ResultHandle args = body.newArray(Object.class, method.parametersCount());
        for (int i = 0; i < method.parametersCount(); i++) {
          body.writeArrayValue(args, i, body.getMethodParam(i));
        }
        ResultHandle result = body.invokeVirtualMethod(
            MethodDescriptor.ofMethod(
                RemoteTransport.class,
                "call",
                CompletionStage.class,
                String.class,
                String.class,
                Object[].class,
                String.class,
                boolean.class
            ),
            body.readInstanceField(transport.getFieldDescriptor(), body.getThis()),
            body.load(binding),
            body.load(key(contract, method)),
            args,
            body.load(canonical(method.returnType().asParameterizedType().arguments().getFirst())),
            body.load(event)
        );
        body.returnValue(result);
      }
    }
  }

  private static void export(
      BuildProducer<GeneratedBeanBuildItem> beans,
      ClassInfo contract,
      MethodInfo method,
      String target,
      int number
  ) {
    try (ClassCreator cc = ClassCreator
      .builder()
      .classOutput(new GeneratedBeanGizmoAdaptor(beans))
      .className(contract.name() + "_RemoteOperation" + number)
      .interfaces(RemoteOperation.class)
      .build()) {
      cc.addAnnotation(Singleton.class);
      MethodCreator key = cc.getMethodCreator("key", String.class);
      key.returnValue(key.load(key(contract, method)));
      MethodCreator name = cc.getMethodCreator("contract", String.class);
      name.returnValue(name.load(contract.name().toString()));
      MethodCreator arity = cc.getMethodCreator("arity", int.class);
      arity.returnValue(arity.load(method.parametersCount()));
      MethodCreator invoke = cc.getMethodCreator("invoke", CompletionStage.class, JsonNode[].class);
      ResultHandle[] args = new ResultHandle[method.parametersCount()];
      for (int i = 0; i < args.length; i++) {
        args[i] = invoke.invokeStaticMethod(
            MethodDescriptor.ofMethod(RemoteTransport.class, "decode", Object.class, JsonNode.class, String.class),
            invoke.readArrayValue(invoke.getMethodParam(0), i),
            invoke.load(canonical(method.parameterType(i)))
        );
      }
      ResultHandle instance;
      if (contract.hasAnnotation(EVENTS)) {
        var local = cc.getFieldCreator("localEvents", contract.name().toString()).setModifiers(0);
        local.addAnnotation(Inject.class);
        local.addAnnotation(ProducedComponentEvents.class);
        local.addAnnotation(RemoteBinding.class).addValue("value", "");
        instance = invoke.readInstanceField(local.getFieldDescriptor(), invoke.getThis());
      } else {
        instance = invoke.invokeStaticMethod(
            MethodDescriptor.ofMethod(ComponentInstances.class, "get", Object.class, Class.class),
            invoke.loadClass(target)
        );
      }
      MethodDescriptor descriptor = MethodDescriptor.of(method);
      invoke.returnValue(invoke.invokeInterfaceMethod(
          MethodDescriptor.ofMethod(
              contract.name().toString(),
              method.name(),
              descriptor.getReturnType(),
              descriptor.getParameterTypes()
          ),
          invoke.checkCast(instance, contract.name().toString()),
          args
      ));
    }
  }

  private static String key(ClassInfo contract, MethodInfo method) {
    return contract.name() + "#" + method.name() + method.parameterTypes() + ":" + method.returnType();
  }

  private static String canonical(Type type) {
    if (type.kind() == Type.Kind.PARAMETERIZED_TYPE) {
      return type.name()
          + "<"
          + String.join(",", type
            .asParameterizedType()
            .arguments()
            .stream()
            .map(RemotingProcessor::canonical)
            .toList())
          + ">";
    }
    return type.toString();
  }

  private static void collect(IndexView index, ClassInfo contract, Map<String, MethodInfo> methods) {
    for (Type parent : contract.interfaceTypes()) {
      require(parent.kind() == Type.Kind.CLASS, "Generic contract inheritance is unsupported: " + contract.name());
      ClassInfo info = index.getClassByName(parent.name());
      require(info != null, "Contract parent is not indexed: " + parent.name());
      collect(index, info, methods);
    }
    for (MethodInfo method : contract.methods()) {
      if (!Modifier.isStatic(method.flags())
          && !Modifier.isPrivate(method.flags())
          && !method.name().startsWith("<")) {
        methods.put(method.name() + method.parameterTypes(), method);
      }
    }
  }

  private static void validate(IndexView index, Type type, Set<String> dtos, Set<String> visiting) {
    if (type.kind() == Type.Kind.PRIMITIVE) {
      return;
    }
    String name = type.name().toString();
    if (Set
      .of(
          "java.lang.Void",
          "java.lang.String",
          "java.lang.Boolean",
          "java.lang.Byte",
          "java.lang.Short",
          "java.lang.Integer",
          "java.lang.Long",
          "java.lang.Float",
          "java.lang.Double",
          "java.lang.Character",
          "java.util.UUID"
      )
      .contains(name)) {
      return;
    }
    if (type.kind() == Type.Kind.PARAMETERIZED_TYPE) {
      require(Set.of("java.util.List", "java.util.Map").contains(name), "Unsupported remote generic DTO: " + type);
      List<Type> args = type.asParameterizedType().arguments();
      require(
          !name.equals("java.util.Map") || args.getFirst().equals(Type.create(String.class)),
          "Remote map keys must be strings: " + type
      );
      for (Type arg : args) {
        validate(index, arg, dtos, visiting);
      }
      return;
    }
    require(type.kind() == Type.Kind.CLASS, "Unsupported remote DTO: " + type);
    ClassInfo info = index.getClassByName(type.name());
    require(
        info != null && Modifier.isPublic(info.flags()) && (info.isRecord() || info.isEnum()),
        "Remote DTO must be an indexed public record or enum: " + type
    );
    require(visiting.add(name), "Cyclic remote DTO: " + type);
    dtos.add(name);
    if (info.isRecord()) {
      for (RecordComponentInfo field : info.recordComponents()) {
        validate(index, field.type(), dtos, visiting);
      }
    }
    visiting.remove(name);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException("JaCoMo: " + message);
    }
  }
}
