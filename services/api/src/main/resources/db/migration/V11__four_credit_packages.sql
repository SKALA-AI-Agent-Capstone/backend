-- 충전 금액권을 4종으로 줄인다: 100 = 5,000원, 500 = 23,000원, 1,000 = 42,000원, 2,000 = 70,000원.
-- 많이 살수록 크레딧당 값이 크게 내려간다 (50원, 46원, 42원, 35원). 할인율은 정가(크레딧당 50원) 대비다.
-- 지난 주문 기록이 가리킬 수 있으므로 쓰지 않는 금액권은 지우지 않고 비활성으로 돌린다.

-- 비활성 금액권은 표시 순서를 뒤로 미뤄 새 순서(1~4)와 겹치지 않게 한다
UPDATE app.credit_packages
SET active = false, display_order = display_order + 100
WHERE credits NOT IN (100, 500, 1000, 2000);

UPDATE app.credit_packages SET amount_krw = 5000, discount_percent = 0, display_order = 1, active = true WHERE credits = 100;

INSERT INTO app.credit_packages (credits, amount_krw, discount_percent, display_order, active)
VALUES (500, 23000, 8, 2, true)
ON CONFLICT (credits) DO UPDATE
SET amount_krw = EXCLUDED.amount_krw,
    discount_percent = EXCLUDED.discount_percent,
    display_order = EXCLUDED.display_order,
    active = true;

UPDATE app.credit_packages SET amount_krw = 42000, discount_percent = 16, display_order = 3, active = true WHERE credits = 1000;
UPDATE app.credit_packages SET amount_krw = 70000, discount_percent = 30, display_order = 4, active = true WHERE credits = 2000;
