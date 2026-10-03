INSERT INTO tariff_plan (plan_id, name, plan_type, monthly_fee, data_gb, validity_days, active, version) VALUES
 ('PREPAID_299',  'Unlimited 299 (28 days)',    'PREPAID',  299.00, 42,  28, TRUE,  0),
 ('PREPAID_719',  'Unlimited 719 (84 days)',    'PREPAID',  719.00, 168, 84, TRUE,  0),
 ('POSTPAID_599', 'Postpaid Family 599',        'POSTPAID', 599.00, 105, 30, TRUE,  0),
 ('POSTPAID_999', 'Postpaid Premium 999 + OTT', 'POSTPAID', 999.00, 300, 30, TRUE,  0),
 ('LEGACY_199',   'Legacy Talktime 199',        'PREPAID',  199.00, 2,   28, FALSE, 0)
ON CONFLICT (plan_id) DO NOTHING;

INSERT INTO subscriber (msisdn, name, circle, status, plan_id, updated_at, version) VALUES
 ('9876543210', 'Arun Kumar',  'KARNATAKA',  'ACTIVE',    'POSTPAID_599', CURRENT_TIMESTAMP, 0),
 ('9123456780', 'Priya Raman', 'TAMIL_NADU', 'ACTIVE',    'POSTPAID_599', CURRENT_TIMESTAMP, 0),
 ('9988776655', 'Rahul Verma', 'DELHI',      'ACTIVE',    'PREPAID_299',  CURRENT_TIMESTAMP, 0),
 ('9000011111', 'Meena Iyer',  'KARNATAKA',  'SUSPENDED', 'PREPAID_719',  CURRENT_TIMESTAMP, 0),
 ('9445566778', 'Karthik S',   'TAMIL_NADU', 'ACTIVE',    'PREPAID_299',  CURRENT_TIMESTAMP, 0)
ON CONFLICT (msisdn) DO NOTHING;
