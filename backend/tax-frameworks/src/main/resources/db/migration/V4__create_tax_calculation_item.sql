-- Migration: V4__create_tax_calculation_item.sql
-- SPEC-TAX-006 §2.4 — Per-component tax amounts within a calculation.

CREATE TABLE tax_calculation_item (
    id              UUID            NOT NULL,
    calculation_id  UUID            NOT NULL,
    tax_type        VARCHAR(8)      NOT NULL,
    taxable_base    NUMERIC(19, 4)  NOT NULL,
    rate            NUMERIC(19, 9)  NOT NULL,
    tax_amount      NUMERIC(19, 2)  NOT NULL,
    sort_order      SMALLINT        NOT NULL,

    CONSTRAINT tax_calculation_item_pk
        PRIMARY KEY (id),

    CONSTRAINT tax_calculation_item_calculation_fk
        FOREIGN KEY (calculation_id) REFERENCES tax_calculation (id),

    CONSTRAINT tax_calculation_item_tax_type_check
        CHECK (tax_type IN ('CBS', 'IBS')),

    CONSTRAINT tax_calculation_item_taxable_base_positive_check
        CHECK (taxable_base > 0),

    CONSTRAINT tax_calculation_item_rate_range_check
        CHECK (rate > 0 AND rate < 1),

    CONSTRAINT tax_calculation_item_tax_amount_non_negative_check
        CHECK (tax_amount >= 0),

    CONSTRAINT tax_calculation_item_sort_order_check
        CHECK (sort_order >= 0)
);

COMMENT ON TABLE  tax_calculation_item            IS 'Per-component tax amounts within a calculation. Ordered by sort_order ascending.';
COMMENT ON COLUMN tax_calculation_item.sort_order IS 'Deterministic ordering: CBS=0, IBS=1. Derived from TaxType.ordinal(). Query must ORDER BY sort_order ASC.';
COMMENT ON COLUMN tax_calculation_item.rate       IS 'Stored at scale 9 (NUMERIC(19,9)); matches TaxRate domain value.';

-- Supports JOIN from tax_calculation → items; must ORDER BY sort_order
CREATE INDEX tax_calculation_item_calculation_id_idx
    ON tax_calculation_item (calculation_id, sort_order);
