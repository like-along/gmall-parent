package com.atguigu.gmall.activity.receiver;

import com.atguigu.gmall.activity.mapper.SeckillGoodsMapper;
import com.atguigu.gmall.activity.service.SeckillGoodsService;
import com.atguigu.gmall.common.constant.MqConst;
import com.atguigu.gmall.common.constant.RedisConst;
import com.atguigu.gmall.common.util.DateUtil;
import com.atguigu.gmall.model.activity.SeckillGoods;
import com.atguigu.gmall.model.activity.UserRecode;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rabbitmq.client.Channel;
import lombok.SneakyThrows;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * @author DL
 * @create 2020-04-06 9:43
 * 根据条件查询秒杀商品
 */
@Component
public class SeckillReceiver {

    @Autowired
    private SeckillGoodsMapper seckillGoodsMapper;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private SeckillGoodsService seckillGoodsService;


    //监听消息队列
    @SneakyThrows
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConst.QUEUE_TASK_1),
            exchange = @Exchange(value = MqConst.EXCHANGE_DIRECT_TASK),
            key = {MqConst.ROUTING_TASK_1}
    ))
    public void importGoods(Message message, Channel channel){
        //什么商品算是秒杀商品
        QueryWrapper<SeckillGoods> seckillGoodsQueryWrapper = new QueryWrapper<>();
        //秒杀商品审核状态1 审核通过 其他不通过
        //判断商品库存数量大于0
        seckillGoodsQueryWrapper.eq("status",1).gt("stock_count",0);
        //当前时间必须为第二天0点秒杀的商品
        seckillGoodsQueryWrapper.eq("DATE_FORMAT(start_time,'%Y-%m-%d')", DateUtil.formatDate(new Date()));

        List<SeckillGoods> seckillGoodsList = seckillGoodsMapper.selectList(seckillGoodsQueryWrapper);
        //当前几个放入被秒杀的商品集合数据
        //将商品几个数据放入缓存

        if(seckillGoodsList!=null && seckillGoodsList.size()>0){
            //循环遍历当前集合
            for (SeckillGoods seckillGoods : seckillGoodsList) {
                //遍历出来放入缓存
                //判断缓存中是否已经存在当前的秒杀商品 如果存在不放人, 没有则放入当前商品
                Boolean flag = redisTemplate.boundHashOps(RedisConst.SECKILL_GOODS).hasKey(seckillGoods.getSkuId().toString());
                //缓存中已经有秒杀商品
                if(flag){
                    continue;
                }
                //将秒杀商品放入缓存 hash
                redisTemplate.boundHashOps(RedisConst.SECKILL_GOODS).put(seckillGoods.getSkuId().toString(),seckillGoods);

                //如何保障商品不超卖
                seckillGoods.getStockCount();//数量储存到redis_list队列
                for (Integer i = 0; i < seckillGoods.getStockCount(); i++) {
                    //
                    redisTemplate.boundListOps(RedisConst.SECKILL_STOCK_PREFIX+seckillGoods.getSkuId()).leftPush(seckillGoods.getSkuId().toString());


                }
                //发布一个消息 当前所有商品状态位都是1
                //初始化商品状态1 都是可用秒杀的
                redisTemplate.convertAndSend("seckillpush", seckillGoods.getSkuId()+":1");

            }
            //手动确认当前消息
            channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);
        }


    }

    @SneakyThrows
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConst.QUEUE_SECKILL_USER,durable = "true"),
            exchange = @Exchange(value = MqConst.EXCHANGE_DIRECT_SECKILL_USER),
            key = {MqConst.ROUTING_SECKILL_USER}
    ))

    public void seckillUser(UserRecode userRecode,Message message,Channel channel){
        if(null != userRecode){
          //预下单
            seckillGoodsService.seckillOrder(userRecode.getSkuId(), userRecode.getUserId());


            //手动确认消息处理
            channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);
        }

    }

    @SneakyThrows
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConst.QUEUE_TASK_18),
            exchange = @Exchange(value = MqConst.EXCHANGE_DIRECT_TASK),
            key = {MqConst.ROUTING_TASK_18}
    ))

    public void clearRedis(Message message, Channel channel){

        //清空缓存
        //数据库中状态status 2表示活动结束
        //查询结束秒杀商品
        QueryWrapper<SeckillGoods> seckillGoodsQueryWrapper = new QueryWrapper<>();
        seckillGoodsQueryWrapper.eq("status",1);//1表示正在进行秒杀活动
        seckillGoodsQueryWrapper.le("end_time",new Date());

        List<SeckillGoods> seckillGoodsList = seckillGoodsMapper.selectList(seckillGoodsQueryWrapper);

        //删除数据
        for (SeckillGoods seckillGoods : seckillGoodsList) {
            redisTemplate.delete(RedisConst.SECKILL_STOCK_PREFIX+seckillGoods.getSkuId());
        }
        //清空秒杀订单
        redisTemplate.delete(RedisConst.SECKILL_ORDERS);
        //清空秒杀商品
        redisTemplate.delete(RedisConst.SECKILL_GOODS);
        //删除用户秒杀订单
        redisTemplate.delete(RedisConst.SECKILL_ORDERS_USERS);

        //处理数据库
        SeckillGoods seckillGoods = new SeckillGoods();
        seckillGoods.setStatus("2");
        seckillGoodsMapper.update(seckillGoods,seckillGoodsQueryWrapper);

        //手动确认
        channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);
    }

}
