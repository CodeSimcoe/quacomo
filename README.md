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


