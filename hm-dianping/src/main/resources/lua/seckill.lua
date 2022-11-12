-- 1. 参数列表
-- 1.1 优惠券ID
local voucherId = ARGV[1]
-- 1.2 用户ID
local userId = ARGV[2]

-- 2. 数据Key
-- 2.1 库存Key
local stockKey = 'seckill:stock:' .. voucherId -- lua脚本中的字符串拼接方式
-- 2.2 订单Key
local orderKey = 'seckill:order:' .. voucherId

-- 3. 脚本业务
-- 3.1 判断库存是否充足 get stockKey
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 3.2 库存不足，返回1
    return 1
end

-- 3.2 判断用户是否下单
if (tonumber(redis.call('sismember', orderKey, userId)) == 1) then
    -- 3.3 存在说明是重复下单
    return 2
end

-- 3.4 扣减库存
redis.call('incrby', stockKey, -1)

-- 3.5 下单（保存用户）
redis.call('sadd', orderKey, userId)

return 0
