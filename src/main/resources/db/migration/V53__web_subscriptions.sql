-- Website subscriptions snapshot their own delivery details; WhatsApp ones keep using the customer record.
ALTER TABLE subscriptions
    ADD COLUMN channel varchar(20) NOT NULL DEFAULT 'WHATSAPP' CHECK (channel IN ('WHATSAPP','WEB')),
    ADD COLUMN delivery_name varchar(100),
    ADD COLUMN delivery_address varchar(2000),
    ADD COLUMN delivery_preference varchar(20) CHECK (delivery_preference IN ('GATE','DOOR','IN_PERSON')),
    ADD COLUMN loaf_preference varchar(20) CHECK (loaf_preference IN ('SLICED','WHOLE'));

CREATE INDEX subscriptions_customer_status ON subscriptions(customer_id, status);
