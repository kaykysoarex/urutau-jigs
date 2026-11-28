package br.com.urutau.jigs.quotes;

import static br.com.urutau.jigs.ai.AssistantTools.*;
import static br.com.urutau.jigs.ai.AssistantTools.Tool.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import br.com.urutau.jigs.quotes.QuoteDraftDtos.Content;
import br.com.urutau.jigs.quotes.QuoteDraftDtos.Save;
import br.com.urutau.jigs.quotes.QuoteDraftDtos.Source;
import br.com.urutau.jigs.quotes.QuoteDtos.ItemInput;
import org.springframework.stereotype.Service;

@Service
public class AssistantDrafts {
    private final QuoteDraftService drafts;
    private final QuoteService quotes;
    private final QuoteDuplication duplication;
    private final AssistantCommercial commercial;

    AssistantDrafts(QuoteDraftService drafts, QuoteService quotes, QuoteDuplication duplication, AssistantCommercial commercial) {
        this.drafts = drafts;
        this.quotes = quotes;
        this.duplication = duplication;
        this.commercial = commercial;
    }

    public record Basis(UUID id, Long version, List<ItemInput> items, String notes) {}
    public record Seed(long quote, List<ItemInput> items, String notes) {}

    public Seed fromHistory(Actor actor, long customer) {
        actor.name();
        long number = commercial.latestCustomerRecord(customer, true);
        var prepared = duplication.prepare(number);
        return new Seed(number, prepared.content().items(), prepared.content().notes());
    }

    public Basis basis(Actor actor, UUID id) {
        String admin = actor.name();
        if (id == null) return new Basis(null, null, List.of(), "");
        var opened = drafts.open(id, admin);
        if (opened.content().pendingReview()) throw invalid("Abra o rascunho e revise primeiro a interpretação pendente.");
        return new Basis(id, opened.summary().version(), opened.content().items(), opened.content().notes());
    }

    public UUID latest(Actor actor) {
        return drafts.list(actor.name()).stream().findFirst().map(QuoteDraftDtos.Listed::id)
            .orElseThrow(() -> invalid("Nenhum rascunho ativo para este administrador."));
    }

    public Reply list(Actor actor) {
        var summaries = drafts.list(actor.name());
        return new Reply(summaries.isEmpty() ? "Você não tem rascunhos ativos." : "Seus rascunhos mais recentes.",
            summaries.stream().limit(12).map(row -> new Card(row.clientName() == null ? "Cliente a definir" : row.clientName(),
                List.of(row.source() == Source.IA ? "Com IA" : "Manual", row.quantity() + " unidades"),
                List.of(draftLink(row.id(), "Abrir rascunho")))).toList());
    }

    public Reply open(Actor actor, UUID id) {
        var opened = drafts.open(id, actor.name());
        var summary = opened.summary();
        var lines = new ArrayList<>(List.of(summary.source() == Source.IA ? "Com IA" : "Manual",
            summary.quantity() + " unidades · " + summary.combinations() + " combinações"));
        if (summary.estimatedTotal() != null) lines.add("Estimativa atual: " + AssistantCommercial.currency(summary.estimatedTotal()));
        if (opened.variants().stream().anyMatch(item -> !item.active())) lines.add("Há combinações indisponíveis. Revise antes de emitir.");
        return new Reply("Rascunho localizado.", List.of(new Card(summary.clientName() == null ? "Cliente a definir" : summary.clientName(),
            List.copyOf(lines), List.of(draftLink(id, "Abrir rascunho"), new Action("preview", id.toString(), "Pré-visualizar PDF")))))
            .context(opened.content().customer() == null ? null : opened.content().customer().id(), null, id);
    }

    public Reply duplicate(Actor actor, long number) {
        String admin = actor.name();
        var prepared = duplication.prepare(number);
        var lines = new ArrayList<>(List.of("Origem: orçamento nº " + number,
            "Cliente: " + prepared.content().client().name(), "Novo rascunho com os preços vigentes."));
        var expected = estimate(prepared.content().items());
        lines.addAll(expected.details());
        if (!prepared.unavailable().isEmpty()) lines.add("Indisponíveis para revisão: " + String.join(", ", prepared.unavailable()));
        return new Proposal(DRAFT_DUPLICATE, "RASCUNHO", "novo", new Card("Duplicar orçamento", List.copyOf(lines)),
            "origem #" + number, snapshot(prepared.content().items()), id -> {
                unchanged(prepared, duplication.prepare(number));
                unchanged(expected, estimate(prepared.content().items()));
                duplication.save(prepared, id, admin);
                return open(actor, id);
            }).preview();
    }

    public Reply write(Actor actor, Basis basis, List<ItemInput> items, String notes, Long customerId, String name, Boolean useAi) {
        String admin = actor.name();
        var opened = basis.id() == null ? null : drafts.open(basis.id(), admin);
        if (opened != null) unchanged(basis.version(), opened.summary().version());
        var old = opened == null ? null : opened.content();
        var client = old == null ? new QuoteDtos.Client(name, null, null, null) : old.client();
        var ref = old == null ? null : old.customer();
        if (customerId != null) {
            var customer = commercial.customer(customerId);
            if (!customer.active()) throw invalid("Selecione um cliente ativo.");
            ref = new CustomerDtos.Ref(customer.id(), customer.version());
            client = new QuoteDtos.Client(customer.name(), customer.phone(), null, null);
        }
        if (items.isEmpty() && (client.name() == null || client.name().isBlank()) && notes.isBlank() && old == null) {
            throw invalid("Informe o cliente, os produtos ou uma observação para iniciar o rascunho.");
        }
        var initial = QuoteDuplication.initial(client, items, notes, ref);
        var content = old == null ? initial : new Content(client, items, notes, old.editor(), old.pendingAi(), ref,
            useAi == null ? old.source() : useAi ? Source.IA : Source.MANUAL, old.ai());
        var details = new ArrayList<>(List.of("Cliente: " + (client.name() == null ? "a definir" : client.name()),
            "Origem: " + (content.source() == Source.IA ? "IA" : "manual")));
        if (old != null) details.add("Itens atuais: " + snapshot(old.items()));
        details.add("Itens propostos: " + snapshot(content.items()));
        if (!notes.equals(basis.notes())) details.add("Observações: " + notes);
        var proposedEstimate = estimate(items);
        details.addAll(proposedEstimate.details());
        Tool kind = opened == null ? DRAFT_CREATE : DRAFT_UPDATE;
        return new Proposal(kind, "RASCUNHO", basis.id() == null ? "novo" : basis.id().toString(),
            new Card(opened == null ? "Criar rascunho" : "Alterar rascunho", List.copyOf(details)), snapshot(basis.items()),
            snapshot(items), id -> {
                UUID target = basis.id() == null ? id : basis.id();
                if (content.customer() != null) unchanged(content.customer().version(), commercial.customer(content.customer().id()).version());
                unchanged(proposedEstimate, estimate(items));
                drafts.save(target, admin, new Save(basis.version(), content));
                return open(actor, target);
            }).preview();
    }

    public Reply discard(Actor actor, UUID id) {
        String admin = actor.name();
        var opened = drafts.open(id, admin);
        return new Proposal(DRAFT_DISCARD, "RASCUNHO", id.toString(), new Card("Descartar rascunho",
            List.of("Cliente: " + opened.summary().clientName(), opened.summary().quantity() + " unidades",
                "A edição será encerrada.")), snapshot(opened.content().items()), "encerrado", ignored -> {
            drafts.discard(id, admin, opened.summary().version());
            return new Reply("Rascunho descartado.");
        }).preview();
    }

    public Reply issue(Actor actor, UUID id) {
        String admin = actor.name();
        var opened = drafts.open(id, admin);
        var body = opened.content();
        if (body.pendingReview() || body.client().name() == null || body.client().name().isBlank() || body.items().isEmpty()) {
            throw invalid("Informe o nome do cliente e revise os itens antes de emitir.");
        }
        var preview = quotes.preview(body.items());
        if (preview.missingPrices() > 0) throw invalid("Cadastre todos os preços da tabela aplicada antes de emitir.");
        var reference = body.customer() == null ? null : new CustomerDtos.Selection(body.customer().id(), false, false);
        var details = new ArrayList<>(List.of("Cliente: " + body.client().name(), "Tabela: " + preview.priceTier(),
            "Total: " + AssistantCommercial.currency(preview.total()), "A emissão atribui um número e encerra o rascunho."));
        details.addAll(preview.items().stream().map(item -> item.model() + " " + item.weight() + " g · cor "
            + item.color() + " · " + item.quantity() + " unidades · " + AssistantCommercial.currency(item.subtotal())).toList());
        return new Proposal(ISSUE, "ORCAMENTO", "novo", new Card("Emitir orçamento definitivo", List.copyOf(details)),
            "rascunho " + id, preview.priceTier() + " · " + preview.quantity() + " unidades · " + preview.total(), request -> {
                var result = quotes.issue(new QuoteDtos.IssueRequest(body.client(), body.items(), request, preview.reviewToken(),
                    id, opened.summary().version(), reference), admin);
                return commercial.record(actor, result.number());
            }).preview();
    }

    private record Estimate(String token, List<String> details) {}

    private Estimate estimate(List<ItemInput> items) {
        if (items.isEmpty()) return new Estimate("", List.of());
        try {
            var preview = quotes.preview(items);
            var total = preview.total() == null ? "A definir" : AssistantCommercial.currency(preview.total());
            var lines = new ArrayList<>(List.of("Estimativa: " + preview.quantity() + " unidades · " + preview.priceTier() + " · " + total));
            preview.items().forEach(item -> lines.add(item.model() + " " + item.weight() + " g · cor " + item.color()
                + " · " + item.quantity() + " unidades"));
            return new Estimate(preview.reviewToken(), List.copyOf(lines));
        } catch (org.springframework.web.server.ResponseStatusException unavailable) {
            if (!unavailable.getStatusCode().is4xxClientError()) throw unavailable;
            return new Estimate("", List.of("Há itens indisponíveis. Confira o catálogo antes de emitir."));
        }
    }

    private static String snapshot(List<ItemInput> items) {
        return items.stream().map(item -> "#" + item.variantId() + " × " + item.quantity())
            .collect(java.util.stream.Collectors.joining(", "));
    }
    private static Action draftLink(UUID id, String text) { return new Action("draft", id.toString(), text); }
}
