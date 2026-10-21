CREATE TABLE app.credit_packages (
  credits integer PRIMARY KEY CHECK (credits > 0),
  amount_krw integer NOT NULL CHECK (amount_krw > 0),
  discount_percent integer NOT NULL CHECK (discount_percent BETWEEN 0 AND 100),
  display_order integer NOT NULL UNIQUE,
  active boolean NOT NULL DEFAULT true
);

INSERT INTO app.credit_packages (credits, amount_krw, discount_percent, display_order) VALUES
  (100, 5000, 0, 1),
  (200, 9500, 5, 2),
  (300, 13500, 10, 3),
  (400, 17600, 12, 4),
  (600, 25800, 14, 5),
  (800, 33600, 16, 6),
  (1000, 41000, 18, 7),
  (2000, 80000, 20, 8);

CREATE TABLE app.credit_policy (
  id boolean PRIMARY KEY DEFAULT true CHECK (id),
  review_cost integer NOT NULL CHECK (review_cost > 0)
);
INSERT INTO app.credit_policy (id, review_cost) VALUES (true, 100);
