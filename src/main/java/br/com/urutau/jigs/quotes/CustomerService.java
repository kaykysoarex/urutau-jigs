
package br.com.urutau.jigs.quotes;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class CustomerService {
    private final CustomerRepository customers;
    private final QuoteRepository quotes;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    CustomerService(CustomerRepository customers, QuoteRepository quotes, JdbcTemplate jdbc, Clock clock) {
        this.customers = customers;
        this.quotes = quotes;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    CustomerDtos.Page list(String query, Boolean active, int page) {
        var found = search(query, active, page);
        var totals = sales(found.getContent().stream().map(customer -> customer.id).toList());
        return new CustomerDtos.Page(found.getContent().stream().map(customer -> new CustomerDtos.Card(
                customer.brief(), totals.getOrDefault(customer.id, CustomerDtos.Sales.empty()))).toList(), page, found.getTotalPages());
    }

    @Transactional(readOnly = true)
    List<CustomerDtos.Match> matching(String query) {
        if (query.isBlank()) return List.of();
        return search(query, true, 0).getContent().stream().map(CommercialCustomer::match).toList();
    }

    @Transactional(readOnly = true)
    List<CustomerDtos.Match> duplicates(String phone, Long except) {
        String normalized = CustomerPhone.normalize(phone.strip());
        if (normalized == null) return List.of();
        return customers.findByNormalizedPhoneOrderByActiveDescNameAscIdAsc(normalized, page(0)).stream()
                .filter(customer -> !customer.id.equals(except)).map(CommercialCustomer::match).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    CustomerDtos.Detail detail(long id) {
        var customer = require(id);
        var sales = sales(List.of(id)).getOrDefault(id, CustomerDtos.Sales.empty());
        return new CustomerDtos.Detail(customer.view(), new CustomerDtos.Indicators(quotes.countByCustomerId(id),
                quotes.countByCustomerIdAndStatus(id, QuoteDtos.Status.PENDENTE), sales, quotes.lastCustomerOrder(id)));
    }

    @Transactional(readOnly = true)
    CustomerDtos.History history(long id, boolean orders, int page) {
        require(id);
        var history = quotes.customerHistory(id, orders, page(page));
        return new CustomerDtos.History(history.getContent(), page, history.getTotalPages());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    CustomerDtos.View create(CustomerDtos.Input data, String actor) {
        var customer = new CommercialCustomer();
        customer.createdAt = now();
        write(customer, data, actor, "CADASTRO");
        return customer.view();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    CustomerDtos.View edit(long id, CustomerDtos.Change change, String actor) {
        // All writers take the same guard before locking any customer row.
        lockRegistry();
        var customer = locked(id);
        requireVersion(customer, change.version());
        write(customer, change.data(), actor, "EDICAO");
        return customer.view();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    CustomerDtos.View activate(long id, CustomerDtos.Activation change, String actor) {
        var customer = locked(id);
        requireVersion(customer, change.version());
        if (customer.active != change.active()) {
            customer.active = change.active();
            customer.updatedAt = now();
            customers.flush();
            audit(customer, change.active() ? "REATIVACAO" : "DESATIVACAO", actor);
        }
        return customer.view();
    }

    @Transactional(readOnly = true)
    CustomerDtos.Match snapshot(long id) {
        return require(id).match();
    }

    void serializeRegistration(CustomerDtos.Selection selection) {
        if (selection != null && selection.wantsRegistration()) lockRegistry();
    }

    // Participates in the same transaction as issuance or confirmation.
    Long resolve(CustomerDtos.Selection selection, QuoteDtos.Client snapshot, String actor) {
        if (selection == null) return null;
        if (selection.id() != null) {
            if (selection.wantsRegistration()) throw bad("Escolha um cadastro existente ou um novo cadastro.");
            var customer = locked(selection.id());
            if (!customer.active) throw conflict("O cliente está inativo. Reative o cadastro ou escolha outro cliente.");
            return customer.id;
        }
        if (!selection.wantsRegistration()) return null;
        return create(new CustomerDtos.Input(snapshot.name(), null, snapshot.phone(), null, snapshot.city(),
                snapshot.state(), null, selection.allowSharedPhone()), actor).id();
    }

    private void write(CommercialCustomer customer, CustomerDtos.Input data, String actor, String action) {
        var snapshot = QuoteService.clean(new QuoteDtos.Client(data.name(), data.phone(), data.city(), data.state()));
        if (snapshot.name() == null) throw bad("Informe o nome ou razão social.");
        var normalized = CustomerPhone.normalize(snapshot.phone());
        if (snapshot.phone() != null && normalized == null) throw bad("Informe um telefone válido com DDD ou código internacional.");
        if (customer.id == null) lockRegistry();
        if (normalized != null && !Objects.equals(normalized, customer.normalizedPhone) && !Boolean.TRUE.equals(data.allowSharedPhone())
                && customers.findByNormalizedPhone(normalized).stream().anyMatch(other -> !other.id.equals(customer.id))) {
            throw conflict("Este telefone já está cadastrado. Selecione o cadastro existente ou confirme que o número é compartilhado.");
        }
        customer.fill(data, snapshot, normalized, now());
        customers.saveAndFlush(customer);
        audit(customer, action, actor);
    }

    private void audit(CommercialCustomer customer, String action, String actor) {
        jdbc.update("insert into customer_audit_event (customer_id, action, actor, occurred_at) values (?, ?, ?, ?)",
                customer.id, action, actor, Timestamp.from(customer.updatedAt));
    }

    private void lockRegistry() {
        jdbc.queryForObject("select id from customer_write_guard where id = 1 for update", Integer.class);
    }

    private org.springframework.data.domain.Page<CommercialCustomer> search(String query, Boolean active, int page) {
        String search = query.strip();
        String digits = search.matches("[+0-9().\\s-]+") ? search.replaceAll("\\D", "") : "";
        return customers.search(search, digits, Objects.requireNonNullElse(CustomerPhone.normalize(search), ""),
                active, page(page));
    }

    private Map<Long, CustomerDtos.Sales> sales(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return quotes.sales(ids, QuoteDtos.Status.CONFIRMADO).stream()
                .collect(Collectors.toMap(QuoteRepository.CustomerSales::getCustomerId, row ->
                        new CustomerDtos.Sales(row.getOrders(), row.getUnits(), row.getApprovedTotal())));
    }

    private CommercialCustomer require(long id) {
        return customers.findById(id).orElseThrow(CustomerService::missing);
    }

    private CommercialCustomer locked(long id) {
        return customers.lockById(id).orElseThrow(CustomerService::missing);
    }

    private static void requireVersion(CommercialCustomer customer, long version) {
        if (customer.version != version) throw conflict("O cadastro foi alterado. Abra os detalhes novamente.");
    }

    static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static PageRequest page(int index) {
        if (index < 0) throw bad("Informe uma página válida.");
        return PageRequest.of(index, 16);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado.");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
