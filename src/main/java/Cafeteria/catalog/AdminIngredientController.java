package Cafeteria.catalog;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import Cafeteria.catalog.IngredientService.IngredientRef;
import Cafeteria.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/admin/ingredients")
public class AdminIngredientController {

    public record IngredientRequest(@NotBlank @Size(max = 80) String name) {}

    private final TenantContext tenantContext;
    private final IngredientService ingredients;

    public AdminIngredientController(TenantContext tenantContext, IngredientService ingredients) {
        this.tenantContext = tenantContext;
        this.ingredients = ingredients;
    }

    @GetMapping
    public List<IngredientRef> list() {
        return ingredients.list(tenantContext.require().tenantId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IngredientRef create(@Valid @RequestBody IngredientRequest request) {
        return ingredients.create(tenantContext.require().tenantId(), request.name());
    }

    @PutMapping("/{id}")
    public IngredientRef update(@PathVariable UUID id, @Valid @RequestBody IngredientRequest request) {
        return ingredients.update(tenantContext.require().tenantId(), id, request.name());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        ingredients.delete(tenantContext.require().tenantId(), id);
    }
}
