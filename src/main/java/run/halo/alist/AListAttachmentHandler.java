package run.halo.alist;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.Extension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Constant;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.core.extension.attachment.endpoint.AttachmentHandler;
import run.halo.app.core.extension.attachment.endpoint.SimpleFilePart;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.halo.app.infra.utils.JsonUtils;

/**
 * AListAttachmentHandler
 *
 * @author： <a href="https://roozen.top">Roozen</a>
 * @date: 2024/7/3
 */
@Slf4j
@Extension
@Component
public class AListAttachmentHandler implements AttachmentHandler {

    @Autowired
    ReactiveExtensionClient client;

    AListProperties properties = null;
    Map<String, WebClient> webClients = new HashMap<>();

    private final Cache<String, String> tokenCache = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.DAYS)
        .build();

    @Override
    public Mono<Attachment> upload(UploadContext uploadContext) {
        FilePart file = uploadContext.file();
        return Mono.just(uploadContext)
            .filter(context -> this.shouldHandle(context.policy()))
            .map(UploadContext::configMap)
            .map(this::getProperties)
            .flatMap(this::auth)
            .flatMap(token -> webClients.get(properties.getSite())
                .put()
                .uri("/api/fs/put")
                .header("Authorization", token)
                .header("File-Path", UriUtils.encodePath(
                    properties.getPath() + "/" + file.name(),
                    StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.headers().getContentLength())
                .body(file.content().cache(), DataBuffer.class)
                .retrieve()
                .bodyToMono(
                    new ParameterizedTypeReference<AListResult<String>>() {
                    })
                .flatMap(response -> {
                    if (response.getCode().equals("200")) {
                        log.info("[AList Info] :  Upload file {} successfully",
                            file.name());
                        return Mono.just(token);
                    }
                    return Mono.error(new AListException(response.getMessage()));
                })
            )
            .flatMap(token -> webClients.get(properties.getSite())
                .post()
                .uri("/api/fs/get")
                .header("Authorization", token)
                .body(Mono.just(
                        AListGetFileInfoReq
                            .builder()
                            .path(properties.getPath() + "/" + file.name())
                            .build()),
                    AListGetFileInfoReq.class)
                .retrieve()
                .bodyToMono(
                    new ParameterizedTypeReference<AListResult<AListGetFileInfoRes>>() {
                    })
                .flatMap(response -> {
                    if (response.getCode().equals("200")) {
                        log.info("[AList Info] :  Got file {} successfully",
                            file.name());
                        return Mono.just(response);
                    }
                    return Mono.error(new AListException(response.getMessage()));
                }))
            .map(response -> {
                var metadata = new Metadata();
                metadata.setName(UUID.randomUUID().toString());
                metadata.setAnnotations(
                    Map.of(Constant.EXTERNAL_LINK_ANNO_KEY,
                        UriUtils.encodePath(
                            properties.getSite() + "/d" + properties.getPath() + "/"
                                + response.getData().getName(),
                            StandardCharsets.UTF_8)));
                var spec = new Attachment.AttachmentSpec();
                SimpleFilePart simpleFilePart = (SimpleFilePart) file;
                spec.setDisplayName(simpleFilePart.filename());
                spec.setMediaType(simpleFilePart.mediaType().toString());
                spec.setSize(response.getData().getSize());

                var attachment = new Attachment();
                attachment.setMetadata(metadata);
                attachment.setSpec(spec);
                return attachment;
            });
    }

    public Mono<String> auth(AListProperties properties) {
        this.properties = properties;
        WebClient webClient = webClients.computeIfAbsent(properties.getSite(),
            k -> WebClient.builder()
                .baseUrl(k)
                .build());

        String secretName = properties.getSecretName();
        if (tokenCache.getIfPresent(secretName) != null) {
            return Mono.just(
                Objects.requireNonNull(tokenCache.getIfPresent(properties.getTokenKey())));
        }

        return client.fetch(Secret.class, secretName)
            .switchIfEmpty(Mono.error(new AListException(
                "Secret " + secretName + " not found")))
            .flatMap(secret -> {
                var stringData = secret.getStringData();
                var usernameKey = "username";
                var passwordKey = "password";
                if (stringData == null
                    || !(stringData.containsKey(usernameKey) && stringData.containsKey(
                    passwordKey))) {
                    return Mono.error(new AListException(
                        "Secret " + secretName
                            + " does not have username or password key"));
                }
                var username = stringData.get(usernameKey);
                var password = stringData.get(passwordKey);
                return webClient.post()
                    .uri("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Mono.just(
                        AListLoginReq.builder()
                            .username(username)
                            .password(password)
                            .build()), AListLoginReq.class)
                    .retrieve()
                    .bodyToMono(
                        new ParameterizedTypeReference<AListResult<AListLoginRes>>() {
                        });
            }).flatMap(response -> {
                if (response.getCode().equals("200")) {
                    log.info("[AList Info] :  Login successfully");
                    return Mono.just(
                        tokenCache.get(properties.getTokenKey(),
                            k -> response.getData().getToken()));
                }
                return Mono.error(new AListException(
                    "Wrong Username Or Password"));
            });
    }

    private AListProperties getProperties(ConfigMap configMap) {
        var settingJson = configMap.getData().getOrDefault("default", "{}");
        return JsonUtils.jsonToObject(settingJson, AListProperties.class);
    }

    @Override
    public Mono<Attachment> delete(DeleteContext deleteContext) {
        return Mono.just(deleteContext).filter(context -> this.shouldHandle(context.policy()))
            .map(DeleteContext::configMap)
            .map(this::getProperties)
            .flatMap(this::auth)
            .flatMap(token -> webClients.get(properties.getSite())
                .post()
                .uri("/api/fs/remove")
                .header("Authorization", token)
                .body(Mono.just(AListRemoveFileReq.builder()
                    .dir(properties.getPath())
                    .names(List.of(deleteContext.attachment().getSpec().getDisplayName()))
                    .build()), AListGetFileInfoReq.class)
                .retrieve()
                .bodyToMono(
                    new ParameterizedTypeReference<AListResult<String>>() {
                    })
                .flatMap(response -> {
                    if (response.getCode().equals("200")) {
                        log.info("[AList Info] :  Delete file {} successfully",
                            deleteContext.attachment().getSpec().getDisplayName());
                        return Mono.just(token);
                    }
                    return Mono.error(new AListException(response.getMessage()));
                })
            )
            .map(token -> deleteContext.attachment());
    }

    @Override
    public Mono<URI> getSharedURL(Attachment attachment, Policy policy, ConfigMap configMap,
        Duration ttl) {
        return getPermalink(attachment, policy, configMap);
    }

    @Override
    public Mono<URI> getPermalink(Attachment attachment, Policy policy, ConfigMap configMap) {
        return Mono.just(policy).filter(this::shouldHandle)
            .flatMap(p -> auth(getProperties(configMap)))
            .flatMap(token -> webClients.get(properties.getSite())
                .post()
                .uri("/api/fs/get")
                .header("Authorization", tokenCache.getIfPresent(properties.getTokenKey()))
                .body(Mono.just(
                        AListGetFileInfoReq
                            .builder()
                            .path(properties.getPath() + "/" + attachment.getSpec().getDisplayName())
                            .build()),
                    AListGetFileInfoReq.class)
                .retrieve()
                .bodyToMono(
                    new ParameterizedTypeReference<AListResult<AListGetFileInfoRes>>() {
                    })
                .flatMap(response -> {
                    if (response.getCode().equals("200")) {
                        log.info("[AList Info] :  Got file {} successfully",
                            attachment.getSpec().getDisplayName());
                        return Mono.just(response);
                    }
                    return Mono.error(new AListException(response.getMessage()));
                }))
            .map(response -> URI.create(UriUtils.encodePath(
                properties.getSite() + "/d" + properties.getPath() + "/"
                    + response.getData().getName(),
                StandardCharsets.UTF_8)));
    }

    boolean shouldHandle(Policy policy) {
        if (policy == null || policy.getSpec() == null ||
            policy.getSpec().getTemplateName() == null) {
            return false;
        }
        String templateName = policy.getSpec().getTemplateName();
        return "alist".equals(templateName);
    }

    public Mono<Void> removeTokenCache(AListProperties properties) {
        tokenCache.invalidate(properties.getTokenKey());
        return Mono.empty();
    }
}