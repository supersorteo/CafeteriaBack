package Cafeteria.catalog;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import Cafeteria.shared.ApiException;

final class CatalogSupport {

    private CatalogSupport() {}

    static void requireVersion(Long requested, Long current) {
        if (requested == null) {
            throw ApiException.badRequest("version_required", "Falta la versión del registro");
        }
        if (!Objects.equals(requested, current)) {
            throw ApiException.conflict("version_conflict",
                    "El registro fue modificado por otra persona. Recarga e inténtalo de nuevo.");
        }
    }

    static void requireCompleteOrder(List<UUID> requested, List<UUID> existing) {
        boolean valid = requested.size() == existing.size()
                && new HashSet<>(requested).size() == requested.size()
                && new HashSet<>(existing).equals(new HashSet<>(requested));
        if (!valid) {
            throw ApiException.badRequest("invalid_order",
                    "El orden debe incluir exactamente todos los elementos, sin repetidos ni ajenos");
        }
    }
}
