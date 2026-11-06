# JaCoMo — Java Component Model pour Quarkus

Un composant est une classe concrète annotée `@Component`. Il est géré par ArC
comme un singleton : les services sont injectés directement, sans client proxy.
Ne lui ajoutez pas un autre scope CDI.

```java
@ComponentServices
public interface Greeting {
    String hello(String name);
}

@ComponentEvents
public interface Greetings {
    void greeted(String name);
}

@Component
public class Greeter implements Greeting {
    @ProducedComponentEvents
    Greetings events;

    public String hello(String name) {
        events.greeted(name);
        return "Bonjour " + name;
    }
}

@Component
public class Listener implements Greetings {
    @UsedComponentServices
    Greeting greeting;

    public void greeted(String name) {
        System.out.println(name);
    }
}
```

Chaque type public doit être placé dans son propre fichier Java.

- `@ComponentServices` marque une interface de services. Un champ annoté
  `@UsedComponentServices` nécessite exactement un composant fournisseur.
- `@ComponentEvents` marque une interface d'événements. Les composants qui
  l'implémentent sont automatiquement abonnés, y compris via héritage.
- `@ProducedComponentEvents` injecte une implémentation générée de cette interface.
  Les méthodes retournent `void` (synchrone) ou `CompletionStage<Void>` (asynchrone).
  Leurs paramètres sont transmis tels quels.
- Pour les méthodes `void`, la diffusion est synchrone, sur le thread appelant, dans l'ordre des noms de
  classes des abonnés. Sans abonné, l'appel ne fait rien. Une exception remonte
  immédiatement et interrompt la diffusion aux abonnés suivants.

L'extension découvre et valide les contrats pendant l'augmentation Quarkus.
Elle transforme les champs en points d'injection CDI et génère le bytecode des
dispatchers, avec une liste fixe d'abonnés. Il n'y a ni proxy dynamique, ni
recherche de méthodes par réflexion. Le dispatcher récupère chaque singleton
au moment de l'appel via ArC : cela permet notamment à l'abonné d'utiliser un
service du composant émetteur sans créer de cycle d'injection à sa construction.
Les événements ne doivent pas être émis pendant la construction des composants.
Les cycles directs entre champs de services restent soumis aux règles CDI.

Les contrats génériques et les méthodes d'événements génériques ne sont pas
pris en charge. Aucun mécanisme de QoS, de rejeu ou de reprise
sur erreur n'est ajouté pour cette première version.

Pour un événement asynchrone, utilisez explicitement `CompletionStage<Void>` :

```java
@ComponentEvents
public interface AsyncGreetings {
    CompletionStage<Void> greeted(String name);
}

@Component
public class AsyncListener implements AsyncGreetings {
    public CompletionStage<Void> greeted(String name) {
        System.out.println(name);
        return CompletableFuture.completedFuture(null);
    }
}
```

Importez `java.util.concurrent.CompletionStage` et `CompletableFuture`.
Le producteur appelle sa référence `@ProducedComponentEvents` normalement :

```java
events.greeted("Alice").whenComplete((_, failure) -> {
    if (failure != null) {
        // Traiter l'échec de diffusion.
    }
});
```

Chaque abonné reçoit une tâche sur le `ManagedExecutor` Quarkus, fourni par la
dépendance `quarkus-smallrye-context-propagation` incluse dans l'extension.
La stage retournée attend la fin de tous les abonnés, y compris leurs stages
asynchrones. Une exception levée, une stage échouée, un retour `null` ou un rejet
de soumission fait échouer la diffusion sans annuler les autres livraisons.
En cas d'échecs multiples, une seule cause est exposée. Sans abonné, la stage
est déjà terminée avec succès.

Les abonnés peuvent s'exécuter en parallèle et recevoir plusieurs événements
simultanément ; leur état doit donc supporter ces accès concurrents. Aucun
ordre de livraison n'est garanti. Les paramètres ne sont pas copiés : évitez
de modifier des objets partagés après l'émission. Chaque abonné doit retourner
une stage non nulle représentant la fin de son traitement. Les méthodes `void`
et `CompletionStage<Void>` peuvent coexister dans la même interface.

Exécuter les tests de l'extension depuis la racine : `mvn test`.



## Cross-JVM calls with HTTP remoting

Add the optional extension to each application that sends or receives remote calls:

```xml
<dependency>
    <groupId>com.codesimcoe.quacomo</groupId>
    <artifactId>quacomo-remoting</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Local-only applications can continue depending on `quacomo`; no HTTP dependency
is added to the core extension.

### Contracts and bindings

`@Remotable` is a marker with **no id or version attributes**. The fully qualified
interface name identifies the contract. Method names, parameter types and return
types identify operations, including inherited methods and overloads. Renaming a
contract or changing a signature changes its wire identity; both sides must use
compatible contracts. DTO schemas are not negotiated or versioned in this release.

Publish a shared contracts JAR containing interfaces and DTOs, without component
implementations. Ensure it is indexed by Quarkus (a Jandex index, `beans.xml`, or
`quarkus.index-dependency` configuration).

```java
@Remotable
@ComponentServices
public interface Inventory {
    CompletionStage<Stock> getStock(ProductId product);
}

public record ProductId(String value) {}
public record Stock(int available) {}

@Remotable
@ComponentEvents
public interface OrderEvents {
    CompletionStage<Void> orderPlaced(OrderPlaced event);
}

public record OrderPlaced(String orderId, List<String> products) {}
```

Each public type belongs in its own Java file. All remote methods must return
`CompletionStage<T>`; remote events specifically return `CompletionStage<Void>`.
Synchronous remote calls and generic contract/method declarations are rejected
at build time.

On JVM A, inject explicit named remote bindings:

```java
@Component
public class Checkout {
    @UsedComponentServices(remote = "warehouse")
    Inventory inventory;

    @ProducedComponentEvents(remote = "fulfilment")
    OrderEvents events;
}
```

```properties
# JVM A: URLs can use normal hostnames or Kubernetes Service DNS names.
quacomo.remote.destinations.warehouse.url=https://warehouse:8443
quacomo.remote.destinations.warehouse.token=${WAREHOUSE_TOKEN}
quacomo.remote.destinations.fulfilment.url=https://fulfilment:8443
quacomo.remote.destinations.fulfilment.token=${FULFILMENT_TOKEN}
```

On the receiving JVM, implement the interfaces with ordinary `@Component`
classes. Explicitly allowlist the contracts and configure a nonempty bearer token:

```properties
# JVM B: replace these names with your contracts' fully qualified Java names.
quacomo.remote.exports=com.example.Inventory,com.example.OrderEvents
quacomo.remote.server.token=${REMOTING_TOKEN}
```

Incoming requests use `POST /quacomo/remote` on the normal Quarkus HTTP server.
The configured destination base URL includes any HTTP root path. With no server
token, requests are rejected; with no exports, no operation is exposed. Use HTTPS
for traffic outside a trusted development environment. Configure the receiving
Quarkus HTTP server's TLS normally. The client uses the JVM's default trust store
and validates certificates and hostnames; custom client TLS registries/mTLS are
not implemented yet. Bearer-token authorization currently applies to the whole
export allowlist, not individual contracts.

Leaving `remote` empty retains local injection/delivery. Remote bindings never
fall back to local providers, even if an implementation also exists in the same
JVM. A client application needs no local service implementation. Existing direct
local services can still be synchronous and local events can still return `void`.

### Delivery and failures

A remote service invokes one configured destination. A remote event can fan out
to several named destinations:

```java
@ProducedComponentEvents(remote = "billing,audit")
OrderEvents events;
```

Each destination receives one HTTP request. Its local event dispatcher invokes
all local subscribers; it does not forward the received event to remote bindings.
The returned stage waits for every configured destination and its subscriber
stages. One failure makes the aggregate fail without cancelling other deliveries.
Calling a load-balanced Kubernetes Service delivers to one selected pod, not every
pod. To broadcast to multiple destinations, name them explicitly. Remote events
without local subscribers complete successfully.

Calls are transient request/reply exchanges: no retries, persistent queues, replay,
ordering guarantees or exactly-once guarantee. Timeout or disconnection does not
prove the receiver did not execute the operation. Cancelling a caller's stage does
not undo remote work. Arguments cross the wire by value; no component references,
Java exceptions, transaction context or CDI request context are transported.

Remote execution failures use `RemoteCallException` with code
`REMOTE_EXECUTION_FAILED`; exception messages/stacks remain on the receiver.
Protocol/HTTP errors and missing destination configuration fail the returned
stage. Network errors and timeouts retain their underlying asynchronous transport
exception. The API does not serialize arbitrary Java exception subclasses.

### Serialization and limits

JSON supports primitives/wrappers, strings, UUIDs, public enums, indexed public
records, `List<T>` and `Map<String,T>` of these types, plus nullable references.
`Void` represents completion without a result. Other DTO types, arrays, cycles,
unresolved generic types and polymorphic object graphs are rejected at build time.
The generated clients and server operations contain fixed types; the peer cannot
supply class names to select a deserializer. Jackson annotation interpretation and
default polymorphic typing are disabled in the dedicated remoting mapper. DTO
reflection metadata is registered for native images; native execution has not
been verified by the JVM integration suite.

```properties
# Defaults, in milliseconds/bytes/requests:
quacomo.remote.timeout-ms=10000
quacomo.remote.max-payload-bytes=1048576
quacomo.remote.max-in-flight=128
# Also bound HTTP request buffering before the endpoint parses its body:
quarkus.http.limits.max-body-size=1M
```

The client has a five-second connection timeout and no automatic redirects.
Request/response payloads and outstanding outbound/inbound operations are bounded.
An inbound slot remains occupied until the handler stage finishes, including if
the caller disconnects. Blocking remote implementations run on a worker thread.
Handlers and singleton components must support concurrent invocations.

### Verification

`mvn verify` runs core regression tests, remoting validation/injection tests, and
packages an integration fixture that launches **two independent JVMs over TCP**.
The fixture verifies remote service results and process IDs, nested JSON DTOs,
inherited/overloaded methods, nulls, event fan-out, authentication, unknown
operations, execution failures, timeouts and receiver shutdown. Its processes are
stopped after the test; logs are under `remoting/integration-tests/target/`.
