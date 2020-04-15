package com.atguigu.gmall.order.service.impl;

import com.alibaba.fastjson.JSON;
import com.atguigu.gmall.common.constant.MqConst;
import com.atguigu.gmall.common.service.RabbitService;
import com.atguigu.gmall.common.util.HttpClientUtil;
import com.atguigu.gmall.model.enums.OrderStatus;
import com.atguigu.gmall.model.enums.ProcessStatus;
import com.atguigu.gmall.model.order.OrderDetail;
import com.atguigu.gmall.model.order.OrderInfo;
import com.atguigu.gmall.order.mapper.OrderDetailMapper;
import com.atguigu.gmall.order.mapper.OrderInfoMapper;
import com.atguigu.gmall.order.service.OrderService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import springfox.documentation.spring.web.json.Json;

import java.util.*;

/**
 * @author mqx
 * @date 2020/3/28 15:45
 */
@Service
public class OrderServiceImpl extends ServiceImpl<OrderInfoMapper, OrderInfo> implements OrderService {

    @Autowired
    private OrderInfoMapper orderInfoMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private RabbitService rabbitService;

    @Value("${ware.url}")
    private String wareUrl;

    @Override
    @Transactional
    public Long saveOrderInfo(OrderInfo orderInfo) {
        // 保存数据orderInfo,orderDetail
        // orderInfo 中，没有总金额，订单状态，userId,第三方交易编号，创建时间，过期时间，订单的主题，进程状态。
        // 计算总金额赋值给OrderInfo
        // 不需要重新赋值订单明细集合，因为在页面传递过来的时候，会自动封装好了
        // 根据springmvc 对象传值的规则，自动赋值到orderInfo.orderDetailList
        orderInfo.sumTotalAmount();
        orderInfo.setOrderStatus(OrderStatus.UNPAID.name());
        // 用户Id 通过控制器获取的！
        String outTradeNo =  "ATGUIGU" + System.currentTimeMillis() + "" + new Random().nextInt(1000);
        orderInfo.setOutTradeNo(outTradeNo);
        orderInfo.setCreateTime(new Date());
        // 过期时间 1 天
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DATE,1);
        orderInfo.setExpireTime(calendar.getTime());
        // 订单主题：可用给个固定的字符串 或者 获取订单明细中的名称
        List<OrderDetail> orderDetailList = orderInfo.getOrderDetailList();
        StringBuffer tradeBody = new StringBuffer();
        for (OrderDetail orderDetail : orderDetailList) {
            tradeBody.append(orderDetail.getSkuName()+"");
        }
        if (tradeBody.toString().length()>100){
            orderInfo.setTradeBody(tradeBody.toString().substring(0,100));
        }
        orderInfo.setTradeBody(tradeBody.toString());
        // 设置进程状态
        orderInfo.setProcessStatus(ProcessStatus.UNPAID.name());
        orderInfoMapper.insert(orderInfo);

        // orderDetail 没有orderId = orderInfo.id
        for (OrderDetail orderDetail : orderDetailList) {
            // 防止主键冲突! id 自动增长
            // orderInfo.getId() 之所以能获取到Id 是因为在前面先做了插入数据
            orderDetail.setOrderId(orderInfo.getId());
            // 插入数据
            orderDetailMapper.insert(orderDetail);
        }
        // 发送消息
        rabbitService.sendDelayMessage(MqConst.EXCHANGE_DIRECT_ORDER_CANCEL,MqConst.ROUTING_ORDER_CANCEL,orderInfo.getId(),MqConst.DELAY_TIME);

        // 返回我们的订单id
        return orderInfo.getId();
    }

    @Override
    public String getTradeNo(String userId) {
        // 定义流水号
        String tradeNo = UUID.randomUUID().toString().replace("-","");
        // 将tradeNo 放入缓存
        // 定义缓存的key
        String tradeNoKey = "user:"+userId+":tradeCode";
        // 存储流水号
        redisTemplate.opsForValue().set(tradeNoKey,tradeNo);
        // 返回流水号
        return tradeNo;
    }

    /**
     *
     * @param tradeCodeNo 页面提交过来的流水号
     * @param userId 获取缓存的流水号的key
     * @return
     */
    @Override
    public boolean checkTradeCode(String tradeCodeNo, String userId) {
        // 定义流水号在缓存的key
        String tradeNoKey = "user:"+userId+":tradeCode";
        String tradeNoRedis = (String) redisTemplate.opsForValue().get(tradeNoKey);
        // 返回比较结果
        return tradeNoRedis.equals(tradeCodeNo);
    }

    /**
     *
     * @param userId 获取缓存的流水号的key
     */
    @Override
    public void deleteTradeNo(String userId) {
        // 删除缓存流水号
        // 定义流水号在缓存的key
        String tradeNoKey = "user:"+userId+":tradeCode";
        redisTemplate.delete(tradeNoKey);
    }

    @Override
    public boolean checkStock(Long skuId, Integer skuNum) {
        // 调用库存系统的接口 http://localhost:9001/hasStock?skuId=xxx&num=xxx 远程调用
        String result = HttpClientUtil.doGet(wareUrl + "/hasStock?skuId=" + skuId + "&num=" + skuNum);
        // 返回结果
        return "1".equals(result);
    }

    @Override
    public void execExpiredOrder(Long orderId) {
        // 调用方法 状态
        updateOrderStatus(orderId,ProcessStatus.CLOSED);
    }

    @Override
    public void updateOrderStatus(Long orderId, ProcessStatus processStatus) {
        // 准备更新数据
        // update order_info set order_status = CLOSED ,process_status=CLOSED where id = orderId;
        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setId(orderId);
        orderInfo.setProcessStatus(processStatus.name());
        orderInfo.setOrderStatus(processStatus.getOrderStatus().name());
        orderInfoMapper.updateById(orderInfo);

    }

    @Override
    public OrderInfo getOrderInfo(Long orderId) {
        // 订单主表
        OrderInfo orderInfo = orderInfoMapper.selectById(orderId);
        // 查询订单明细
        QueryWrapper<OrderDetail> orderDetailQueryWrapper = new QueryWrapper<>();
        orderDetailQueryWrapper.eq("order_id",orderId);
        List<OrderDetail> orderDetails = orderDetailMapper.selectList(orderDetailQueryWrapper);
        // 将订单明细集合放入订单中
        orderInfo.setOrderDetailList(orderDetails);
        return orderInfo;
    }

    @Override
    public void sendOrderStatus(Long orderId) {
        updateOrderStatus(orderId,ProcessStatus.NOTIFIED_WARE);//已通知仓库
        //发送json字符串
        String wareJson = initWareOrder(orderId);
        //发送消息
        rabbitService.sendMessage(MqConst.EXCHANGE_DIRECT_WARE_STOCK,MqConst.ROUTING_WARE_STOCK,wareJson);

    }
    //通过订单id 查询orderInfo 转化为字符串
    private String initWareOrder(Long orderId){
        //获取orderId 获取到orderInfo
        OrderInfo orderInfo = getOrderInfo(orderId);
        Map map= initWareOrder(orderInfo);
        //返回JSON字符串

        return null;
    }

    /**
     * 转换orderInfo中的字段map
     * @param orderInfo
     * @return
     */
    public Map initWareOrder(OrderInfo orderInfo) {
        HashMap<String, Object> map = new HashMap<>();
        map.put("orderId", orderInfo.getId());
        map.put("consignee", orderInfo.getConsignee());
        map.put("consigneeTel", orderInfo.getConsigneeTel());
        map.put("orderComment", orderInfo.getOrderComment());
        map.put("orderBody", orderInfo.getTradeBody());
        map.put("deliveryAddress", orderInfo.getDeliveryAddress());
        map.put("paymentWay", "2");

        ArrayList<Object> arrayList = new ArrayList<>();
        //获取原始数据
        List<OrderDetail> orderDetailList = orderInfo.getOrderDetailList();
        for (OrderDetail orderDetail : orderDetailList) {
            //声明一个map集合
            HashMap<String, Object> hashMap = new HashMap<>();
            hashMap.put("skuId", orderDetail.getSkuId());
            hashMap.put("skuNum", orderDetail.getSkuNum());
            hashMap.put("skuName", orderDetail.getSkuName());
            arrayList.add(hashMap);

        }

        //订单明细
        map.put("details",arrayList);

        return map;
    }

    @Override
    public List<OrderInfo> orderSplit(long orderId, String wareSkuMap) {
        //声明子订单集合
        ArrayList<OrderInfo> subOrderInfoList = new ArrayList<>();

        //原始订单
        OrderInfo orderInfoOrigin = getOrderInfo(orderId);
        //json
        List<Map> mapList = JSON.parseArray(wareSkuMap, Map.class);
        if(mapList!=null && mapList.size()>0){
            for (Map map : mapList) {
                //获取仓库id
                String wareId = (String) map.get("wareId");
                //获取仓库id对应的商品id
                List<String> skuIdList = (List<String>) map.get("skuIds");
                //子订单
                OrderInfo subOrderInfo = new OrderInfo();
                //给子订单赋值
                BeanUtils.copyProperties(orderInfoOrigin,subOrderInfo);
                //防止订单主键冲突 id为nnull
                subOrderInfo.setId(null);
                //赋值父id
                subOrderInfo.setParentOrderId(orderId);
                //设置一个仓库id
                subOrderInfo.setWareId(wareId);
                //设置子订单明细
                List<OrderDetail> orderDetailList = orderInfoOrigin.getOrderDetailList();
                //声明订单明细几个
                List<OrderDetail> orderDetailArrayList = new ArrayList<>();
                //判断
                if(orderDetailList!=null && orderDetailList.size()>0){
                    for (OrderDetail orderDetail : orderDetailList) {
                        for (String skuId : skuIdList) {
                            if(Long.parseLong(skuId)==orderDetail.getSkuId().longValue()){
                                //添加订单明细
                                orderDetailArrayList.add(orderDetail);

                            }
                        }
                    }
                }
                //子订单几个放入子订单中
                subOrderInfo.setOrderDetailList(orderDetailArrayList);
                //计算价格
                subOrderInfo.sumTotalAmount();
                //子订单保存
                saveOrderInfo(subOrderInfo);
                // 将子订单添加到集合中！
                subOrderInfoList.add(subOrderInfo);

            }
        }
        //修改原始订单状态
        updateOrderStatus(orderId,ProcessStatus.SPLIT);

        return subOrderInfoList;
    }
}
