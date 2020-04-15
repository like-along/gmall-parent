package com.atguigu.gmall.activity.service.impl;

import com.atguigu.gmall.activity.mapper.SeckillGoodsMapper;
import com.atguigu.gmall.activity.service.SeckillGoodsService;
import com.atguigu.gmall.common.constant.RedisConst;
import com.atguigu.gmall.common.result.Result;
import com.atguigu.gmall.common.result.ResultCodeEnum;
import com.atguigu.gmall.common.util.CacheHelper;
import com.atguigu.gmall.common.util.MD5;
import com.atguigu.gmall.model.activity.OrderRecode;
import com.atguigu.gmall.model.activity.SeckillGoods;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author DL
 * @create 2020-04-06 11:30
 */
@Service
//@Transactional
public class SeckillGoodsServiceImpl implements SeckillGoodsService {

    @Autowired
    private SeckillGoodsMapper seckillGoodsMapper;

    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    public List<SeckillGoods> findAll() {
        //直接获取缓存中的秒杀数据 获取hash结构中的数据

        List<SeckillGoods> list = redisTemplate.boundHashOps(RedisConst.SECKILL_GOODS).values();
        //返回所有的秒杀商品集合
        return list;
    }

    @Override
    public SeckillGoods getSeckillGoodsById(Long id) {
        //从hash结构中获取id对应数据
        SeckillGoods seckillGoods = (SeckillGoods) redisTemplate.boundHashOps(RedisConst.SECKILL_GOODS).get(id.toString());
        return seckillGoods;
    }

    @Override
    public void seckillOrder(Long skuId, String userId) {
        //判断状态位置
        String state = (String) CacheHelper.get(skuId.toString());
        //售完
        if("0".equals(state)){
            return;
        }
        //用户是否已经下过订单
        //存储用户的key

        Boolean isExist = redisTemplate.opsForValue().setIfAbsent(RedisConst.SECKILL_USER + userId, skuId, RedisConst.SKUKEY_TIMEOUT, TimeUnit.SECONDS);
        if(!isExist){
            return;
        }
        //获取商品
        String goodsId = (String) redisTemplate.boundListOps(RedisConst.SECKILL_STOCK_PREFIX + skuId).rightPop();//吐出一个数据
        //判断goodsId是否合法
        if(StringUtils.isEmpty(goodsId)){

            redisTemplate.convertAndSend("seckillpush",skuId+":0");

            //已经售完
            return;
        }

        //订单记录
        OrderRecode orderRecode = new OrderRecode();
        orderRecode.setUserId(userId);
        orderRecode.setNum(1);//默认一件
        //生成下单码
        orderRecode.setOrderStr(MD5.encrypt(userId));
        //根据商品id查询秒杀商品的方法
        orderRecode.setSeckillGoods(getSeckillGoodsById(skuId));

        //将预留的数据订单存入缓存
        redisTemplate.boundHashOps(RedisConst.SECKILL_ORDERS).put(orderRecode.getUserId(),orderRecode);

        //更新库存
        updateStockCount(orderRecode.getSeckillGoods().getSkuId());


    }

    @Override
    public Result checkOrder(Long skuId, String userId) {
        //检查订单 用户在缓存中是否存在
        Boolean isExist = redisTemplate.hasKey(RedisConst.SECKILL_USER + userId);
        //用户在缓存
        if(isExist){
          //用户下单储存在缓存中
            Boolean isHasKey = redisTemplate.boundHashOps(RedisConst.SECKILL_ORDERS).hasKey(userId);
            if(isHasKey){
                //抢单成功
                OrderRecode orderRecode = (OrderRecode) redisTemplate.boundHashOps(RedisConst.SECKILL_ORDERS).get(userId);
                return Result.build(orderRecode, ResultCodeEnum.SECKILL_SUCCESS);//抢单成功

            }
        }
        //判断订单
        Boolean isExistOrder = redisTemplate.boundHashOps(RedisConst.SECKILL_ORDERS_USERS).hasKey(userId);
        if(isExistOrder){//缓存中订单存在
            String orderId = (String) redisTemplate.boundHashOps(RedisConst.SECKILL_ORDERS_USERS).get(userId);

            //返回 下单成功
            return Result.build(orderId,ResultCodeEnum.SECKILL_ORDER_SUCCESS);
        }

        //判断商品的状态
        String status = (String) CacheHelper.get(skuId.toString());
        if("0".equals(status)){
            //已经售完 抢单失败
            return Result.build(null,ResultCodeEnum.SECKILL_FAIL);


        }
        return Result.build(null,ResultCodeEnum.SECKILL_RUN);
    }

    //更新库存
    private void updateStockCount(Long skuId) {
        //获取到当前的库存剩余数量
        Long stockSize = redisTemplate.boundListOps(RedisConst.SECKILL_STOCK_PREFIX + skuId).size();

        //判断  不频繁更新数据
        if(stockSize%2==0){
            SeckillGoods seckillGoods = getSeckillGoodsById(skuId);
            seckillGoods.setStockCount(stockSize.intValue());
            //更新数据库
            seckillGoodsMapper.updateById(seckillGoods);
            //更新缓存
            redisTemplate.boundHashOps(RedisConst.SECKILL_GOODS).put(skuId.toString(),seckillGoods);
        }
    }
}
