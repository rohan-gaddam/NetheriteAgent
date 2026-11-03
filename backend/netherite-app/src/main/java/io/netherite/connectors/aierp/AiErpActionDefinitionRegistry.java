package io.netherite.connectors.aierp;

import io.netherite.common.model.SensitivityLevel;
import io.netherite.ingestion.model.ActionParameterSchema;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static io.netherite.ingestion.model.ActionParameterSchema.array;
import static io.netherite.ingestion.model.ActionParameterSchema.booleanType;
import static io.netherite.ingestion.model.ActionParameterSchema.number;
import static io.netherite.ingestion.model.ActionParameterSchema.object;
import static io.netherite.ingestion.model.ActionParameterSchema.string;

/**
 * Authoritative registry of known AI-ERP operations supported by NetheriteAgent.
 * Enforces canonical naming, strict schema validation, and risk/sensitivity metadata.
 */
@Component
public class AiErpActionDefinitionRegistry {

    private final Map<String, AiErpActionDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, String> aliasMap = new LinkedHashMap<>();

    public AiErpActionDefinitionRegistry() {
        registerReadOnlyOperations();
        registerMutatingOperations();
    }

    private void register(AiErpActionDefinition def, String... aliases) {
        definitions.put(def.getActionId(), def);
        aliasMap.put(def.getActionId().toUpperCase(Locale.ROOT), def.getActionId());
        for (String alias : aliases) {
            aliasMap.put(alias.trim().toUpperCase(Locale.ROOT), def.getActionId());
        }
    }

    public Optional<AiErpActionDefinition> findDefinition(String actionName) {
        if (actionName == null || actionName.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalizeActionName(actionName);
        String canonicalId = aliasMap.get(normalized);
        if (canonicalId != null && definitions.containsKey(canonicalId)) {
            return Optional.of(definitions.get(canonicalId));
        }
        return Optional.ofNullable(definitions.get(normalized));
    }

    public boolean isAiErpAction(String actionName) {
        if (actionName == null || actionName.isBlank()) {
            return false;
        }
        String normalized = normalizeActionName(actionName);
        return aliasMap.containsKey(normalized) || definitions.containsKey(normalized);
    }

    public Map<String, AiErpActionDefinition> getAllDefinitions() {
        return Collections.unmodifiableMap(definitions);
    }

    public String normalizeActionName(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        s = s.replace('-', '_');
        if (s.startsWith("AI_ERP_") && !s.startsWith("AI_ERP:")) {
            s = "AI_ERP:" + s.substring("AI_ERP_".length());
        } else if (s.startsWith("AIERP:") || s.startsWith("AIERP_")) {
            s = "AI_ERP:" + s.substring(6);
        }
        return s;
    }

    private void registerReadOnlyOperations() {
        // 1. Schema discovery (all entities)
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:GET_SCHEMA")
                .httpMethod("GET")
                .pathTemplate("/api/v3.0/p/agent/schema")
                .description("List every entity (type) with its fields in AI-ERP")
                .sensitivityLevel(SensitivityLevel.LOW)
                .readOnly(true)
                .parameterSchema(object(Map.of(), false))
                .build(),
                "GET_SCHEMA", "AI_ERP:SCHEMA", "SCHEMA");

        // 2. Schema discovery (single entity)
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:GET_SCHEMA_ENTITY")
                .httpMethod("GET")
                .pathTemplate("/api/v3.0/p/agent/schema/{entity}")
                .description("Read a single entity's schema and fields by name")
                .sensitivityLevel(SensitivityLevel.LOW)
                .readOnly(true)
                .parameterSchema(object(Map.of(
                        "entity", string().asRequired()
                ), false))
                .build(),
                "GET_SCHEMA_ENTITY", "AI_ERP:SCHEMA_ENTITY", "SCHEMA_ENTITY");

        // 3. Setup status
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:SETUP_STATUS")
                .httpMethod("GET")
                .pathTemplate("/api/v3.0/p/agent/setup-status")
                .description("Check AI-ERP installation and provisioning status")
                .sensitivityLevel(SensitivityLevel.LOW)
                .readOnly(true)
                .parameterSchema(object(Map.of(), false))
                .build(),
                "SETUP_STATUS", "AI_ERP:GET_SETUP_STATUS", "GET_SETUP_STATUS");

        // 4. Resolve price
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:RESOLVE_PRICE")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/resolve-price")
                .description("Resolve effective unit price for a SKU, customer and quantity")
                .sensitivityLevel(SensitivityLevel.LOW)
                .readOnly(true)
                .parameterSchema(object(Map.of(
                        "sku", string().asRequired(),
                        "customer_name", string().asRequired(),
                        "quantity", number().asRequired()
                ), false))
                .build(),
                "RESOLVE_PRICE", "AI_ERP:PRICE");

        // 5. Sales by customer report
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:SALES_BY_CUSTOMER")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/sales-by-customer")
                .description("Sales revenue by customer report")
                .sensitivityLevel(SensitivityLevel.LOW)
                .readOnly(true)
                .parameterSchema(object(Map.of(
                        "from_date", string(),
                        "to_date", string()
                ), false))
                .build(),
                "SALES_BY_CUSTOMER");

        // 6. Sales by product report
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:SALES_BY_PRODUCT")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/sales-by-product")
                .description("Sales quantity and net revenue by product report")
                .sensitivityLevel(SensitivityLevel.LOW)
                .readOnly(true)
                .parameterSchema(object(Map.of(
                        "from_date", string(),
                        "to_date", string()
                ), false))
                .build(),
                "SALES_BY_PRODUCT");

        // 7. Purchases by supplier report
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:PURCHASES_BY_SUPPLIER")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/purchases-by-supplier")
                .description("Purchases cost by supplier report")
                .sensitivityLevel(SensitivityLevel.LOW)
                .readOnly(true)
                .parameterSchema(object(Map.of(
                        "from_date", string(),
                        "to_date", string()
                ), false))
                .build(),
                "PURCHASES_BY_SUPPLIER");

        // 8. Aging receivables report
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:AGING_RECEIVABLES")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/aging-receivables")
                .description("Receivables aging report")
                .sensitivityLevel(SensitivityLevel.LOW)
                .readOnly(true)
                .parameterSchema(object(Map.of(), false))
                .build(),
                "AGING_RECEIVABLES");

        // 9. Customer statement
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:CUSTOMER_STATEMENT")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/customer-statement")
                .description("Customer statement of open invoices and aging")
                .sensitivityLevel(SensitivityLevel.LOW)
                .readOnly(true)
                .parameterSchema(object(Map.of(
                        "customer_name", string().asRequired()
                ), false))
                .build(),
                "CUSTOMER_STATEMENT");

        // 10. General report dispatch
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:REPORT")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/{report_type}")
                .description("Manager report over date range")
                .sensitivityLevel(SensitivityLevel.LOW)
                .readOnly(true)
                .parameterSchema(object(Map.of(
                        "report_type", string().asRequired(),
                        "from_date", string(),
                        "to_date", string()
                ), false))
                .build(),
                "REPORT");

        // 11. EQL Query
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:QUERY")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/query")
                .description("Run an EQL SELECT query against ERP entities")
                .sensitivityLevel(SensitivityLevel.MEDIUM)
                .readOnly(true)
                .parameterSchema(object(Map.of(
                        "eql", string().asRequired(),
                        "parameters", array(object(Map.of(), true))
                ), false))
                .build(),
                "QUERY", "EQL");
    }

    private void registerMutatingOperations() {
        // Line items schema
        ActionParameterSchema lineItemSchema = object(Map.of(
                "sku", string().asRequired(),
                "quantity", number().asRequired(),
                "unitPrice", number(),
                "unitCost", number(),
                "discountPercent", number(),
                "vatCode", string(),
                "lot", string()
        ), true);

        // Place sales order
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:PLACE_SALES_ORDER")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/sales-order")
                .description("Create a sales order with line items")
                .sensitivityLevel(SensitivityLevel.HIGH)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "customer_id", string().asRequired(),
                        "lines", array(lineItemSchema).asRequired(),
                        "order_date", string(),
                        "required_date", string(),
                        "currency", string()
                ), false))
                .build(),
                "PLACE_SALES_ORDER");

        // Invoice sales order
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:INVOICE_SALES_ORDER")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/invoice")
                .description("Turn an existing sales order into a sent invoice")
                .sensitivityLevel(SensitivityLevel.HIGH)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "order_id", string().asRequired(),
                        "due_days", number()
                ), false))
                .build(),
                "INVOICE_SALES_ORDER");

        // Record payment
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:RECORD_PAYMENT")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/payment")
                .description("Record a payment against an invoice")
                .sensitivityLevel(SensitivityLevel.CRITICAL)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "invoice_id", string().asRequired(),
                        "amount", number().asRequired(),
                        "method", string(),
                        "payment_date", string()
                ), false))
                .build(),
                "RECORD_PAYMENT");

        // Place purchase order
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:PLACE_PURCHASE_ORDER")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/purchase-order")
                .description("Create a purchase order with line items")
                .sensitivityLevel(SensitivityLevel.HIGH)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "supplier_id", string().asRequired(),
                        "lines", array(lineItemSchema).asRequired(),
                        "order_date", string(),
                        "expected_date", string()
                ), false))
                .build(),
                "PLACE_PURCHASE_ORDER");

        // Receive purchase order
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:RECEIVE_PURCHASE_ORDER")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/receive")
                .description("Receive goods against a purchase order")
                .sensitivityLevel(SensitivityLevel.HIGH)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "purchase_order_id", string().asRequired(),
                        "lines", array(lineItemSchema).asRequired(),
                        "warehouse_code", string()
                ), false))
                .build(),
                "RECEIVE_PURCHASE_ORDER");

        // Create quote
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:CREATE_QUOTE")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/quote")
                .description("Create a quote with lines and totals")
                .sensitivityLevel(SensitivityLevel.MEDIUM)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "customer_id", string().asRequired(),
                        "lines", array(lineItemSchema).asRequired(),
                        "valid_until", string(),
                        "currency", string()
                ), false))
                .build(),
                "CREATE_QUOTE");

        // Convert quote to order
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:CONVERT_QUOTE_TO_ORDER")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/quote-to-order")
                .description("Convert quote into confirmed sales order")
                .sensitivityLevel(SensitivityLevel.HIGH)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "quote_id", string().asRequired()
                ), false))
                .build(),
                "CONVERT_QUOTE_TO_ORDER");

        // Deliver sales order
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:DELIVER_SALES_ORDER")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/deliver")
                .description("Deliver a sales order (create DDT and decrement stock)")
                .sensitivityLevel(SensitivityLevel.HIGH)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "order_id", string().asRequired(),
                        "lines", array(lineItemSchema).asRequired(),
                        "warehouse_code", string()
                ), false))
                .build(),
                "DELIVER_SALES_ORDER");

        // Create credit note
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:CREATE_CREDIT_NOTE")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/credit-note")
                .description("Issue a credit note against an invoice")
                .sensitivityLevel(SensitivityLevel.CRITICAL)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "invoice_id", string().asRequired(),
                        "amount", number().asRequired(),
                        "reason", string()
                ), false))
                .build(),
                "CREATE_CREDIT_NOTE");

        // Transfer stock
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:TRANSFER_STOCK")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/transfer-stock")
                .description("Transfer stock of a SKU between warehouses")
                .sensitivityLevel(SensitivityLevel.HIGH)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "sku", string().asRequired(),
                        "from_warehouse", string().asRequired(),
                        "to_warehouse", string().asRequired(),
                        "quantity", number().asRequired()
                ), false))
                .build(),
                "TRANSFER_STOCK");

        // Adjust stock
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:ADJUST_STOCK")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/adjust-stock")
                .description("Manually adjust product stock quantity in a warehouse")
                .sensitivityLevel(SensitivityLevel.CRITICAL)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "sku", string().asRequired(),
                        "warehouse_code", string().asRequired(),
                        "new_quantity", number().asRequired(),
                        "reason", string()
                ), false))
                .build(),
                "ADJUST_STOCK");

        // Create customer profile
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:CREATE_CUSTOMER_PROFILE")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/customer-profile")
                .description("Create customer with billing/shipping address and contacts")
                .sensitivityLevel(SensitivityLevel.MEDIUM)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "name", string().asRequired(),
                        "billing", object(Map.of(
                                "line1", string().asRequired(),
                                "city", string().asRequired(),
                                "country", string().asRequired(),
                                "postal_code", string()
                        ), true).asRequired(),
                        "ship", object(Map.of(), true),
                        "contacts", array(object(Map.of(), true)),
                        "email", string(),
                        "currency", string()
                ), false))
                .build(),
                "CREATE_CUSTOMER_PROFILE");

        // Enrich product
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:ENRICH_PRODUCT")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/enrich-product")
                .description("Add barcodes and suppliers to existing product")
                .sensitivityLevel(SensitivityLevel.MEDIUM)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "sku", string().asRequired(),
                        "barcodes", array(object(Map.of(), true)),
                        "suppliers", array(object(Map.of(), true))
                ), false))
                .build(),
                "ENRICH_PRODUCT");

        // Reserve stock
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:RESERVE_STOCK")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/reserve-stock")
                .description("Reserve stock of a SKU for an order")
                .sensitivityLevel(SensitivityLevel.HIGH)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "sku", string().asRequired(),
                        "warehouse_code", string().asRequired(),
                        "quantity", number().asRequired(),
                        "order_id", string()
                ), false))
                .build(),
                "RESERVE_STOCK");

        // Release stock
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:RELEASE_STOCK")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/release-stock")
                .description("Release reserved stock")
                .sensitivityLevel(SensitivityLevel.HIGH)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "sku", string().asRequired(),
                        "warehouse_code", string().asRequired(),
                        "quantity", number().asRequired()
                ), false))
                .build(),
                "RELEASE_STOCK");

        // Reprovision / Headless bootstrap
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:REPROVISION")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/reprovision")
                .description("Re-apply the ERP setup/bootstrap schema and seed data")
                .sensitivityLevel(SensitivityLevel.HIGH)
                .readOnly(false)
                .parameterSchema(object(Map.of(), false))
                .build(),
                "REPROVISION");

        // Create record
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:CREATE_RECORD")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/records/{entity}")
                .description("Create a record of an ERP entity")
                .sensitivityLevel(SensitivityLevel.HIGH)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "entity", string().asRequired()
                ), true))
                .build(),
                "CREATE_RECORD");

        // Update record
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:UPDATE_RECORD")
                .httpMethod("PATCH")
                .pathTemplate("/api/v3.0/p/agent/records/{entity}/{id}")
                .description("Update an existing ERP record by id")
                .sensitivityLevel(SensitivityLevel.HIGH)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "entity", string().asRequired(),
                        "id", string().asRequired()
                ), true))
                .build(),
                "UPDATE_RECORD");

        // Delete record
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:DELETE_RECORD")
                .httpMethod("DELETE")
                .pathTemplate("/api/v3.0/p/agent/records/{entity}/{id}")
                .description("Delete an ERP record by id")
                .sensitivityLevel(SensitivityLevel.CRITICAL)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "entity", string().asRequired(),
                        "id", string().asRequired()
                ), false))
                .build(),
                "DELETE_RECORD");

        // Manage many-to-many relation
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:MANAGE_RELATION")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/relations")
                .description("Add or remove a many-to-many link between two records")
                .sensitivityLevel(SensitivityLevel.HIGH)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "relationId", string().asRequired(),
                        "originId", string().asRequired(),
                        "targetId", string().asRequired(),
                        "remove", booleanType()
                ), false))
                .build(),
                "MANAGE_RELATION");

        // Create purchase request
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:CREATE_PURCHASE_REQUEST")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/purchase-request")
                .description("Create a purchase request with lines")
                .sensitivityLevel(SensitivityLevel.MEDIUM)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "lines", array(lineItemSchema).asRequired(),
                        "requested_by", string()
                ), false))
                .build(),
                "CREATE_PURCHASE_REQUEST");

        // Convert request to PO
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:CONVERT_REQUEST_TO_PO")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/request-to-po")
                .description("Convert an approved purchase request into a purchase order")
                .sensitivityLevel(SensitivityLevel.HIGH)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "request_id", string().asRequired(),
                        "supplier_id", string().asRequired()
                ), false))
                .build(),
                "CONVERT_REQUEST_TO_PO");

        // Register purchase invoice
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:REGISTER_PURCHASE_INVOICE")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/purchase-invoice")
                .description("Register supplier bill against PO with triple match check")
                .sensitivityLevel(SensitivityLevel.HIGH)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "purchase_order_id", string().asRequired(),
                        "lines", array(lineItemSchema).asRequired(),
                        "bill_date", string(),
                        "due_date", string()
                ), false))
                .build(),
                "REGISTER_PURCHASE_INVOICE");

        // Pay supplier bill
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:PAY_SUPPLIER_BILL")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/supplier-payment")
                .description("Pay a supplier bill and update status")
                .sensitivityLevel(SensitivityLevel.CRITICAL)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "bill_id", string().asRequired(),
                        "amount", number().asRequired(),
                        "method", string(),
                        "payment_date", string()
                ), false))
                .build(),
                "PAY_SUPPLIER_BILL");

        // Create supplier credit note
        register(AiErpActionDefinition.builder()
                .actionId("AI_ERP:CREATE_SUPPLIER_CREDIT_NOTE")
                .httpMethod("POST")
                .pathTemplate("/api/v3.0/p/agent/composed/supplier-credit-note")
                .description("Issue supplier credit note against a bill")
                .sensitivityLevel(SensitivityLevel.CRITICAL)
                .readOnly(false)
                .parameterSchema(object(Map.of(
                        "bill_id", string().asRequired(),
                        "amount", number().asRequired(),
                        "reason", string()
                ), false))
                .build(),
                "CREATE_SUPPLIER_CREDIT_NOTE");
    }
}
