#!/usr/bin/env bash
# 还原 G39（train_id=2）的座位、车票、订单和相关缓存，便于重复做抢票实验
source "$(dirname "$0")/common.sh"
sql "UPDATE t_seat SET seat_status=0 WHERE train_id=2; DELETE FROM t_ticket WHERE train_id=2;"
for i in $(seq 0 15); do
  sql "DELETE FROM t_order_$i WHERE train_id=2; DELETE FROM t_order_item_$i WHERE train_id=2; DELETE FROM t_order_item_passenger_$i WHERE id_card LIKE '1101011990%';"
done
rcli DEL "index12306-ticket-service:ticket_availability_token_bucket:2" >/dev/null
redis_del_pattern "index12306-ticket-service:train_station_remaining_ticket:2_*"
redis_del_pattern "index12306-ticket-service:train_station_carriage_remaining_ticket:2_*"
echo ">>> G39 数据已还原，北京南->杭州东 一等座可售：$(sql "select count(*) from t_seat where train_id=2 and seat_type=1 and start_station='北京南' and end_station='杭州东' and seat_status=0")"
