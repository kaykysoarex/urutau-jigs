package br.com.urutau.jigs.ai;

import static br.com.urutau.jigs.ai.AssistantTools.*;
import static br.com.urutau.jigs.ai.AssistantTools.Tool.*;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import br.com.urutau.jigs.catalog.CatalogDtos.*;
import br.com.urutau.jigs.catalog.CatalogService;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;

@Service
class AssistantCatalog {
    private static final int REVIEW_LIMIT = 100;
    private final CatalogService catalog;
    private final Validator validator;

    AssistantCatalog(CatalogService catalog, Validator validator) {
        this.catalog = catalog;
        this.validator = validator;
    }

    Reply list(Actor actor, String description, Boolean active, boolean prices) {
        actor.name();
        var matching = matching(description);
        var available = matching.isEmpty() ? catalog.list() : matching;
        var products = available.stream().filter(model -> active == null || model.active() == active).toList();
        var weights = matching.size() == 1 ? AssistantLanguage.weights(description) : List.<Integer>of();
        var colors = matching.size() == 1 ? AssistantLanguage.colors(description) : List.<String>of();
        var cards = products.stream().limit(12).map(model -> {
            var variants = model.variants().stream()
                .filter(v -> (weights.isEmpty() || weights.contains(v.weight())) && (colors.isEmpty() || colors.contains(v.color())))
                .toList();
            var lines = new ArrayList<String>();
            lines.add(model.active() ? "Modelo ativo" : "Modelo inativo");
            lines.add(variants.size() + " combinações");
            if (prices) {
                lines.add("Padrões · consumidor final " + money(model.defaultRetailPrice()) + " · lojista " + money(model.defaultWholesalePrice()));
                model.weightPrices().stream().filter(row -> weights.isEmpty() || weights.contains(row.weight())).limit(32)
                    .forEach(row -> lines.add(row.weight() + " g · consumidor final " + priceValues(row.retailValues())
                        + " · lojista " + priceValues(row.wholesaleValues()) + " · " + row.combinations() + " combinações"));
            } else {
                lines.add("Varejo: " + range(variants.stream().map(VariantView::retailPrice).toList()));
                lines.add("Lojista: " + range(variants.stream().map(VariantView::wholesalePrice).toList()));
                if (matching.size() == 1) variants.stream().limit(32).forEach(v -> lines.add(describe(v)));
            }
            var action = new Action("nav", prices ? "precos" : "catalogo", prices ? "Abrir tabela de preços" : "Abrir catálogo");
            return new Card(model.name(), lines, List.of(action));
        }).toList();
        return new Reply(cards.isEmpty() ? "Nenhum modelo encontrado para o filtro informado." :
            "Catálogo e preços vigentes. Abra a página para consultar a listagem completa.", cards);
    }

    Reply threshold(Actor actor, Integer units) {
        actor.name();
        int before = catalog.wholesaleThreshold();
        if (units == null) return new Reply("A tabela de lojista começa em " + before + " unidades por orçamento.");
        var change = new Settings(units);
        validate(change);
        var card = new Card("Alterar quantidade mínima de lojista", List.of("Atual: " + before,
            "Novo mínimo: " + units, "Os próximos cálculos usarão o novo limite. Documentos emitidos preservam seus valores."));
        return new Proposal(SETTINGS_UPDATE, "CONFIGURACAO", "lojista", card, Integer.toString(before),
            Integer.toString(units), id -> {
                catalog.updateSettings(change, before);
                return new Reply("Mínimo de lojista atualizado para " + units + " unidades.");
            }).preview();
    }

    Reply create(Actor actor, String name, List<Integer> weights, List<String> colors, BigDecimal retail, BigDecimal wholesale,
                 boolean active, Map<Integer, List<String>> excluded) {
        actor.name();
        if (name == null || name.isBlank() || weights.isEmpty() || colors.isEmpty() || retail == null || wholesale == null) {
            throw invalid("Informe nome, gramaturas, cores e os preços de consumidor final e de lojista.");
        }
        if (catalog.list().stream().anyMatch(product -> normalize(product.name()).equals(normalize(name)))) {
            throw invalid("Já existe um modelo com esse nome. Consulte o catálogo.");
        }
        var combinations = new LinkedHashMap<Key, VariantUpdate>();
        add(combinations, weights, colors);
        excluded.forEach((weight, codes) -> codes.forEach(color -> {
            if (combinations.remove(new Key(weight, color)) == null) throw invalid("Uma das exclusões está fora das combinações geradas.");
        }));
        if (combinations.isEmpty()) throw invalid("Selecione pelo menos uma combinação para cadastrar.");
        var request = new ProductUpdate(name, active, List.copyOf(combinations.values()), new InitialPrices(retail, wholesale));
        validate(request);
        var lines = new ArrayList<>(List.of("Estado: " + (active ? "ativo" : "inativo"), "Gramaturas: " + weights, "Cores: " + colors,
            "Consumidor final: " + money(retail), "Lojista: " + money(wholesale), combinations.size() + " combinações novas"));
        combinations.values().forEach(v -> lines.add(v.weight() + " g · cor " + v.color()));
        if (!excluded.isEmpty()) lines.add("Combinações excluídas da prévia: " + excluded);
        return new Proposal(PRODUCT_CREATE, "CATALOGO", name, new Card("Cadastrar " + name, lines), "",
            request.toString(), ignored -> {
                var saved = catalog.create(request);
                return new Reply("Modelo cadastrado com " + saved.variants().size() + " combinações.",
                    List.of(new Card(saved.name(), List.of(saved.active() ? "Ativo" : "Inativo"), List.of(new Action("product", Long.toString(saved.id()), "Abrir modelo")))));
            }).preview();
    }

    Reply change(Actor actor, String source, String newName, Boolean active, List<Integer> weights, List<String> colors,
                 boolean append, boolean allWeights, boolean allColors, InitialPrices initialPrices) {
        actor.name();
        var model = single(source);
        var desired = new LinkedHashMap<Key, VariantUpdate>();
        model.variants().forEach(v -> desired.put(new Key(v.weight(), v.color()), new VariantUpdate(v.weight(), v.color(), v.active())));
        var lines = new ArrayList<String>();
        boolean modelActive = model.active();
        if (append) {
            if (allWeights) weights = model.variants().stream().filter(VariantView::active).map(VariantView::weight).distinct().toList();
            if (allColors) colors = model.variants().stream().filter(VariantView::active).map(VariantView::color).distinct().toList();
            if (weights.isEmpty() || colors.isEmpty()) throw invalid("Informe as gramaturas e as cores, ou diga explicitamente para usar todas as atuais.");
            var additions = new LinkedHashMap<Key, VariantUpdate>();
            add(additions, weights, colors);
            additions.forEach((key, entry) -> {
                if (desired.putIfAbsent(key, entry) != null) {
                    lines.add("Já cadastrada: " + key);
                    return;
                }
                var existing = model.weightPrices().stream().filter(row -> row.weight() == key.weight()).findFirst();
                if (existing.isPresent() && existing.get().divergent()) {
                    throw invalid("Resolva os preços divergentes de " + key.weight() + " g na tabela de preços antes de adicionar cores.");
                }
                var retail = existing.isPresent() ? existing.get().retailPrice()
                    : initialPrices == null ? model.defaultRetailPrice() : initialPrices.retailPrice();
                var wholesale = existing.isPresent() ? existing.get().wholesalePrice()
                    : initialPrices == null ? model.defaultWholesalePrice() : initialPrices.wholesalePrice();
                lines.add("Criar " + key + " · consumidor final " + money(retail) + " · lojista " + money(wholesale));
            });
            if (desired.size() == model.variants().size()) throw invalid("Todas as combinações informadas já existem.");
        } else if (active != null) {
            if (weights.isEmpty() && colors.isEmpty()) {
                modelActive = active;
                lines.add("Estado do modelo: " + (model.active() ? "ativo" : "inativo") + " → " + (active ? "ativo" : "inativo"));
            } else {
                for (var entry : desired.entrySet()) {
                    var key = entry.getKey();
                    if ((!weights.isEmpty() && !weights.contains(key.weight())) || (!colors.isEmpty() && !colors.contains(key.color()))) continue;
                    lines.add(key + ": " + (entry.getValue().active() ? "ativa" : "inativa") + " → " + (active ? "ativa" : "inativa"));
                    entry.setValue(new VariantUpdate(key.weight(), key.color(), active));
                }
                if (lines.isEmpty()) throw invalid("Nenhuma combinação corresponde aos filtros informados.");
            }
        } else if (newName != null && !newName.isBlank()) {
            lines.add("Nome atual: " + model.name());
            lines.add("Novo nome: " + newName);
        } else {
            throw invalid("Informe o novo nome, as combinações ou o estado desejado.");
        }
        if (lines.size() > REVIEW_LIMIT) throw invalid("Divida a alteração em propostas de até 100 combinações.");
        lines.add("Documentos emitidos mantêm os dados originais.");
        var request = new ProductUpdate(newName == null ? model.name() : newName, modelActive, List.copyOf(desired.values()),
            append ? initialPrices : null);
        validate(request);
        return new Proposal(PRODUCT_UPDATE, "CATALOGO", Long.toString(model.id()), new Card("Alterar " + model.name(), lines),
            model.toString(), request.toString(), ignored -> {
                var saved = catalog.update(model.id(), request, model, null);
                return new Reply("Catálogo atualizado para " + saved.name() + ".");
            }).preview();
    }

    Reply price(Actor actor, String request, boolean wholesale, BigDecimal amount, boolean relative,
                List<Integer> weights, boolean useDefault) {
        actor.name();
        var products = matching(request);
        if (products.isEmpty()) throw invalid("Informe o modelo cadastrado.");
        var updates = new ArrayList<ModelPriceUpdate>();
        var before = new ArrayList<String>();
        var lines = new ArrayList<String>();
        int combinations = 0;
        for (var product : products) {
            var defaults = wholesale ? product.defaultWholesalePrice() : product.defaultRetailPrice();
            var target = useDefault ? defaults : amount;
            if (target == null) throw invalid("Informe o valor do preço. A tabela selecionada pode estar sem preço padrão.");
            var scope = product.weightPrices().stream().filter(row -> weights.isEmpty() || weights.contains(row.weight())).toList();
            if (scope.isEmpty() || !weights.isEmpty() && scope.size() != weights.size()) {
                throw invalid("Uma das gramaturas informadas não pertence ao modelo " + product.name() + ".");
            }
            var weightsToUpdate = new ArrayList<WeightPriceUpdate>();
            for (var row : scope) {
                if (lines.size() >= REVIEW_LIMIT) throw invalid("Divida a alteração em propostas menores.");
                var previous = wholesale ? row.wholesaleValues() : row.retailValues();
                if (relative && (previous.size() != 1 || previous.getFirst() == null)) {
                    throw invalid("Informe o preço final para gramaturas com preços indefinidos ou divergentes.");
                }
                var value = relative ? previous.getFirst().add(target) : target;
                var change = tier(wholesale, value);
                weightsToUpdate.add(new WeightPriceUpdate(row.weight(), change));
                var title = product.name() + " · " + row.weight() + " g · " + row.combinations() + " combinações";
                before.add(title + ": " + priceValues(previous));
                lines.add(title + ": " + priceValues(previous) + " → " + money(value));
                combinations += row.combinations();
            }
            boolean saveDefault = !relative && weights.isEmpty();
            if (saveDefault) {
                before.add(product.name() + " · padrão: " + money(defaults));
                lines.add(product.name() + " · padrão: " + money(defaults) + " → " + money(target));
            }
            updates.add(new ModelPriceUpdate(product.id(), product.version(), saveDefault ? tier(wholesale, target) : null, weightsToUpdate));
        }
        var changes = new WeightPricesUpdate(updates);
        validate(changes);
        lines.add("Total afetado: " + combinations + " combinações. Documentos emitidos conservam os preços originais.");
        String scope = combinations + " combinações";
        return new Proposal(PRICE_UPDATE, "CATALOGO", scope,
            new Card("Alterar preços de " + (wholesale ? "lojista" : "consumidor final"), lines),
            String.join("\n", before), changes.toString(), ignored -> {
                catalog.updateWeightPrices(changes, null);
                return new Reply("Preços atualizados em " + scope + ".");
            }).preview();
    }

    private static PriceChange tier(boolean wholesale, BigDecimal value) {
        return new PriceChange(!wholesale, wholesale ? null : value, wholesale, wholesale ? value : null);
    }

    private static String priceValues(List<BigDecimal> values) {
        var label = String.join(" / ", values.stream().map(AssistantCatalog::money).toList());
        return values.size() > 1 ? "Divergente (" + label + ")" : label;
    }

    private ProductView single(String question) {
        var found = matching(question);
        if (found.size() != 1) throw invalid("Informe o nome completo de um único modelo cadastrado.");
        return found.getFirst();
    }

    private List<ProductView> matching(String request) {
        String text = " " + normalize(request) + " ";
        var candidates = catalog.list().stream().filter(model -> text.contains(" " + normalize(model.name()) + " ")).toList();
        return candidates.stream().filter(model -> candidates.stream().noneMatch(other -> !other.equals(model)
            && (" " + normalize(other.name()) + " ").contains(" " + normalize(model.name()) + " ")
            && normalize(other.name()).length() > normalize(model.name()).length())).toList();
    }

    private static void add(Map<Key, VariantUpdate> target, List<Integer> weights, List<String> colors) {
        if ((long) weights.size() * colors.size() > REVIEW_LIMIT) throw invalid("Divida o cadastro em propostas de até 100 combinações.");
        for (int weight : new LinkedHashSet<>(weights)) for (String color : new LinkedHashSet<>(colors)) {
            var key = new Key(weight, color);
            target.put(key, new VariantUpdate(weight, color, true));
        }
    }

    private void validate(Object value) {
        var violations = validator.validate(value);
        if (!violations.isEmpty()) throw invalid("Revise " + violations.iterator().next().getPropertyPath()
            + ": " + violations.iterator().next().getMessage() + ".");
    }

    static String normalize(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    static String money(BigDecimal value) {
        return value == null ? "A definir" : NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR")).format(value);
    }
    private static String range(List<BigDecimal> prices) {
        var sorted = prices.stream().filter(java.util.Objects::nonNull).sorted().toList();
        return sorted.isEmpty() ? "A definir" : money(sorted.getFirst())
            + (sorted.getFirst().compareTo(sorted.getLast()) == 0 ? "" : " a " + money(sorted.getLast()))
            + (sorted.size() < prices.size() ? " · há valores a definir" : "");
    }
    private static String describe(VariantView variant) {
        return variant.weight() + " g · cor " + variant.color() + " · " + (variant.active() ? "ativa" : "inativa");
    }
    private record Key(int weight, String color) {
        @Override public String toString() { return weight + " g · cor " + color; }
    }
}
