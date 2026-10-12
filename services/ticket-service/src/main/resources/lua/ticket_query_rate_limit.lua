-- 固定窗口限流：判断 + 自增 原子执行
-- KEYS[1] 计数 key；ARGV[1] 窗口内上限；ARGV[2] 窗口长度（毫秒）
local current = redis.call('incr', KEYS[1])
if current == 1 then
    redis.call('pexpire', KEYS[1], ARGV[2])
end
if current > tonumber(ARGV[1]) then
    return 0
end
return 1
