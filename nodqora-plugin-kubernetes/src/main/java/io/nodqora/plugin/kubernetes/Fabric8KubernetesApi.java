package io.nodqora.plugin.kubernetes;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBackend;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedSet;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The one class that talks to a cluster, and the only place a fabric8 type appears.
 *
 * <p><b>No test exercises this.</b> ADR-0099 records at {@link KubernetesApi} instead, and names the
 * gap rather than hiding it: the MVP never proves it can talk to a real cluster, and first contact
 * is a known, bounded, manual step. Everything that has behaviour worth testing sits above the seam.
 *
 * <p>Five list calls per namespace and no {@code watch}. Watch is the native, cheaper,
 * lower-latency mechanism and is what a Kubernetes integration would normally use — ADR-0012 rules
 * it out, because plugins are stateless singletons returning full snapshots while a watch is a
 * stateful delta stream. If latency or apiserver load ever becomes a complaint, <b>ADR-0012 is what
 * reopens, not this class and not ADR-0035's cadence.</b>
 *
 * <p>The client is built per call rather than held: ADR-0014 makes configuration per-environment and
 * ADR-0012 makes the plugin a stateless singleton, so there is no one client to hold. Five minutes
 * apart, the cost is not worth a cache with a lifecycle.
 */
@Component
class Fabric8KubernetesApi implements KubernetesApi {

    private static final Logger log = LoggerFactory.getLogger(Fabric8KubernetesApi.class);

    @Override
    public NamespaceObjects list(KubernetesConfig config, String namespace) {
        try (KubernetesClient client = new KubernetesClientBuilder()
                .withConfig(configure(config))
                .build()) {
            List<ObservedWorkload> workloads = new ArrayList<>();
            client.apps().deployments().inNamespace(namespace).list().getItems().forEach(deployment ->
                    workloads.add(workload(WorkloadKind.DEPLOYMENT, deployment.getMetadata(), podLabels(deployment))));
            client.apps().statefulSets().inNamespace(namespace).list().getItems().forEach(statefulSet ->
                    workloads.add(workload(WorkloadKind.STATEFULSET, statefulSet.getMetadata(), podLabels(statefulSet))));
            client.batch().v1().cronjobs().inNamespace(namespace).list().getItems().forEach(cronJob ->
                    workloads.add(workload(WorkloadKind.CRONJOB, cronJob.getMetadata(), podLabels(cronJob))));

            List<ObservedService> services = client.services().inNamespace(namespace).list().getItems().stream()
                    .map(Fabric8KubernetesApi::service)
                    .toList();
            List<ObservedIngress> ingresses =
                    client.network().v1().ingresses().inNamespace(namespace).list().getItems().stream()
                            .map(Fabric8KubernetesApi::ingress)
                            .toList();

            return new NamespaceObjects(workloads, services, ingresses);
        } catch (RuntimeException e) {
            throw new KubernetesApiException("listing namespace %s failed: %s".formatted(namespace, e.getMessage()), e);
        }
    }

    /**
     * ADR-0035: {@code kubeconfig} is optional and absent means in-cluster. It holds the kubeconfig
     * <em>contents</em>, not a path — ADR-0014's {@code ${file:...}} reference has already read the
     * mounted file by the time configuration is bound.
     */
    private static Config configure(KubernetesConfig config) {
        return config.kubeconfig() == null
                ? Config.autoConfigure(config.context())
                : Config.fromKubeconfig(config.context(), config.kubeconfig(), null);
    }

    private static ObservedWorkload workload(WorkloadKind kind, ObjectMeta metadata, Map<String, String> podLabels) {
        return new ObservedWorkload(
                kind,
                metadata.getNamespace(),
                metadata.getName(),
                creationTimestamp(metadata),
                metadata.getLabels(),
                metadata.getAnnotations(),
                podLabels);
    }

    private static ObservedService service(Service service) {
        return new ObservedService(
                service.getMetadata().getNamespace(),
                service.getMetadata().getName(),
                service.getSpec() == null ? Map.of() : service.getSpec().getSelector());
    }

    private static ObservedIngress ingress(Ingress ingress) {
        SequencedSet<String> services = new LinkedHashSet<>();
        if (ingress.getSpec() != null) {
            backendService(ingress.getSpec().getDefaultBackend()).ifPresent(services::add);
            ingress.getSpec().getRules().stream()
                    .filter(rule -> rule.getHttp() != null)
                    .flatMap(rule -> rule.getHttp().getPaths().stream())
                    .forEach(path -> backendService(path.getBackend()).ifPresent(services::add));
        }
        return new ObservedIngress(
                ingress.getMetadata().getNamespace(), ingress.getMetadata().getName(), List.copyOf(services));
    }

    private static java.util.Optional<String> backendService(IngressBackend backend) {
        return java.util.Optional.ofNullable(backend)
                .map(IngressBackend::getService)
                .map(service -> service.getName())
                .filter(Objects::nonNull);
    }

    private static Map<String, String> podLabels(Object workload) {
        return switch (workload) {
            case Deployment deployment -> templateLabels(deployment.getSpec(), spec -> spec.getTemplate());
            case StatefulSet statefulSet -> templateLabels(statefulSet.getSpec(), spec -> spec.getTemplate());
            case CronJob cronJob -> cronJob.getSpec() == null
                            || cronJob.getSpec().getJobTemplate() == null
                            || cronJob.getSpec().getJobTemplate().getSpec() == null
                    ? Map.of()
                    : templateLabels(
                            cronJob.getSpec().getJobTemplate().getSpec(), spec -> spec.getTemplate());
            default -> Map.of();
        };
    }

    private static <S> Map<String, String> templateLabels(
            S spec, Function<S, io.fabric8.kubernetes.api.model.PodTemplateSpec> template) {
        if (spec == null) {
            return Map.of();
        }
        io.fabric8.kubernetes.api.model.PodTemplateSpec pod = template.apply(spec);
        return pod == null || pod.getMetadata() == null || pod.getMetadata().getLabels() == null
                ? Map.of()
                : pod.getMetadata().getLabels();
    }

    /**
     * A missing or unparseable creation timestamp is not a failure: it costs the object ADR-0021's
     * recency tiebreak, which only matters when a key is contested at all, and sorts it last.
     */
    private static Instant creationTimestamp(ObjectMeta metadata) {
        if (metadata.getCreationTimestamp() == null) {
            return null;
        }
        try {
            return Instant.parse(metadata.getCreationTimestamp());
        } catch (DateTimeParseException e) {
            log.warn("{}/{} has an unparseable creationTimestamp '{}'",
                    metadata.getNamespace(), metadata.getName(), metadata.getCreationTimestamp());
            return null;
        }
    }
}
