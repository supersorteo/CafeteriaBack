package Cafeteria.media;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;

@Component
@ConditionalOnProperty(name = "media.storage", havingValue = "cloudinary")
public class CloudinaryMediaStorage implements MediaStorage {

    private final Cloudinary cloudinary;

    public CloudinaryMediaStorage(
            @Value("${cloudinary.cloud-name}") String cloudName,
            @Value("${cloudinary.api-key}") String apiKey,
            @Value("${cloudinary.api-secret}") String apiSecret) {
        this.cloudinary = new Cloudinary(ObjectUtils.asMap(
                "cloud_name", cloudName,
                "api_key", apiKey,
                "api_secret", apiSecret,
                "secure", true));
    }

    @Override
    public StoredMedia store(UUID tenantId, UUID mediaId, byte[] content, String mimeType) {
        String publicId = "tenants/" + tenantId + "/" + mediaId;
        try {
            Map<?, ?> result = cloudinary.uploader().upload(content, ObjectUtils.asMap(
                    "public_id", publicId,
                    "resource_type", "image",
                    "overwrite", false));
            return new StoredMedia(publicId, (String) result.get("secure_url"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            cloudinary.uploader().destroy(storageKey, ObjectUtils.asMap("resource_type", "image"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
