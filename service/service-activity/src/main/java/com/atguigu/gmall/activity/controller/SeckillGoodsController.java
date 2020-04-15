package com.atguigu.gmall.activity.controller;

import com.atguigu.gmall.activity.service.SeckillGoodsService;
import com.atguigu.gmall.common.constant.MqConst;
import com.atguigu.gmall.common.constant.RedisConst;
import com.atguigu.gmall.common.result.Result;
import com.atguigu.gmall.common.result.ResultCodeEnum;
import com.atguigu.gmall.common.service.RabbitService;
import com.atguigu.gmall.common.util.AuthContextHolder;
import com.atguigu.gmall.common.util.CacheHelper;
import com.atguigu.gmall.common.util.DateUtil;
import com.atguigu.gmall.common.util.MD5;
import com.atguigu.gmall.model.activity.OrderRecode;
import com.atguigu.gmall.model.activity.SeckillGoods;
import com.atguigu.gmall.model.activity.UserRecode;
import com.atguigu.gmall.model.order.OrderDetail;
import com.atguigu.gmall.model.order.OrderInfo;
import com.atguigu.gmall.model.user.UserAddress;
import com.atguigu.gmall.order.client.OrderFeignClient;
import com.atguigu.gmall.user.client.UserFeignClient;
import net.bytebuddy.asm.Advice;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

/**
 * @author DL
 * @create 2020-04-06 11:36
 */
@RestController
@RequestMapping("/api/activity/seckill")
public class SeckillGoodsController {

    @Autowired
    private SeckillGoodsService seckillGoodsService;

    @Autowired
    private RabbitService rabbitService;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private UserFeignClient userFeignClient;

    @Autowired
    private OrderFeignClient orderFeignClient;

    //查询所有秒杀商品
    @GetMapping("/findAll")
    public Result findAll() {
        List<SeckillGoods> list = seckillGoodsService.findAll();

        return Result.ok(list);
    }

    @GetMapping("/getSeckillGoods/{skuId}")
    public Result getSeckillGoods(@PathVariable Long skuId) {
        return Result.ok(seckillGoodsService.getSeckillGoodsById(skuId));
    }

    @GetMapping("auth/getSeckillSkuIdStr/{skuId}")
    public Result getSeckillSkuIdStr(@PathVariable Long skuId, HttpServletRequest request) {
        //获取用户id
        String userId = AuthContextHolder.getUserId(request);
        //通过skuId 获取当前秒杀的商品
        SeckillGoods seckillGoods = seckillGoodsService.getSeckillGoodsById(skuId);
        if (seckillGoods != null) {
            //判断当前是否能够秒杀商品
            //当前开始秒杀时间 秒杀时间的范围 开始-结束
            Date curTime = new Date();//当前时间
            if (DateUtil.dateCompare(seckillGoods.getStartTime(), curTime) && DateUtil.dateCompare(curTime, seckillGoods.getEndTime())) {
                //可以秒杀
                String skuIdStr = MD5.encrypt(userId);
                //返回数据
                return Result.ok(skuIdStr);

            }

        }
        return Result.fail().message("获取下单码失败!!!!!!!!!!!!");


    }

    @PostMapping("auth/seckillOrder/{skuId}")
    public Result seckillOrder(@PathVariable Long skuId,HttpServletRequest request){
        //校验下单码
        //获取用户id
        String userId = AuthContextHolder.getUserId(request);
        //传递过来的下单码
        String skuIdStr = request.getParameter("skuIdStr");

        //防止用户通过浏览器地址直接修改下单ma
        if(!skuIdStr.equals(MD5.encrypt(userId))){
            //请求不合法
            return Result.build(null, ResultCodeEnum.SECKILL_ILLEGAL);
        }
        //判断缓存的标识位置
        String state = (String) CacheHelper.get(skuId.toString());
        if (StringUtils.isEmpty(state)) {
            //请求不合法
            return Result.build(null, ResultCodeEnum.SECKILL_ILLEGAL);
        }
        if ("1".equals(state)) {
            //用户记录
            UserRecode userRecode = new UserRecode();
            userRecode.setUserId(userId);
            userRecode.setSkuId(skuId);

            rabbitService.sendMessage(MqConst.EXCHANGE_DIRECT_SECKILL_USER, MqConst.ROUTING_SECKILL_USER, userRecode);
        } else {
            //已售罄
            return Result.build(null, ResultCodeEnum.SECKILL_FINISH);
        }
        return Result.ok();

    }


    @GetMapping(value = "auth/checkOrder/{skuId}")
    public Result checkOrder(@PathVariable Long skuId,HttpServletRequest request){
        //获取到用户id
        String userId = AuthContextHolder.getUserId(request);
        //商品有商品id 和用户id

        return seckillGoodsService.checkOrder(skuId,userId);
    }


    @GetMapping("auth/trade")
    public Result trade(HttpServletRequest request){
        //获取用户id
        String userId = AuthContextHolder.getUserId(request);
        //获取用户购买的商品
        OrderRecode orderRecode = (OrderRecode) redisTemplate.boundHashOps(RedisConst.SECKILL_ORDERS).get(userId);
        if(null==orderRecode){
            return Result.fail().message("操作失败!!!!");

        }
        //获取里面的数据
        SeckillGoods seckillGoods = orderRecode.getSeckillGoods();

        //获取用户地址列表
        List<UserAddress> userAddressList = userFeignClient.findUserAddressListByUserId(userId);

        //声明一个订单明细
        List<OrderDetail> orderDetailList = new ArrayList<>();

        OrderDetail orderDetail = new OrderDetail();

        orderDetail.setSkuId(seckillGoods.getSkuId());
        orderDetail.setSkuName(seckillGoods.getSkuName());
        //orderDetail.setSkuNum(seckillGoods.getNum());
        orderDetail.setSkuNum(orderRecode.getNum());
        orderDetail.setImgUrl(seckillGoods.getSkuDefaultImg());
        orderDetail.setOrderPrice(seckillGoods.getCostPrice());//秒杀价格

        orderDetailList.add(orderDetail);

        //计算总金额
        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setOrderDetailList(orderDetailList);
        orderInfo.sumTotalAmount();//在这里会报出空指针异常


        //声明map集合
        HashMap<String, Object> map = new HashMap<>();
        map.put("userAddressList",userAddressList);
        map.put("detailArrayList",orderDetailList);
        map.put("totalAmount",orderInfo.getTotalAmount());

        return Result.ok(map);

    }


    //传递json json转jiava
    @PostMapping("auth/submitOrder")
    public Result submitOrder(@RequestBody OrderInfo orderInfo,HttpServletRequest request){
        //获取用户id
        String userId = AuthContextHolder.getUserId(request);
        //下单数据存在缓存  获取下订单数据
        OrderRecode orderRecode = (OrderRecode) redisTemplate.boundHashOps(RedisConst.SECKILL_ORDERS).get(userId);
        if(null == orderRecode){
            return Result.fail().message("很抱歉,下单失败");
        }
        //如果没有用户id 将用户赋值 没有不需要
        //提交订单方法
        Long orderId = orderFeignClient.submitOrder(orderInfo);

        if(null == orderId){
            return Result.fail().message("下订单失败555555！");
        }
        //删除当前用户的下订单记录
        redisTemplate.boundHashOps(RedisConst.SECKILL_ORDERS).delete(userId);
        //记录下当前订单
        redisTemplate.boundHashOps(RedisConst.SECKILL_ORDERS_USERS).put(userId,orderId.toString());
        return Result.ok(orderId);
    }

}