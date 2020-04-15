package com.atguigu.gmall.order.receiver;

import com.alibaba.fastjson.JSON;
import com.atguigu.gmall.common.constant.MqConst;
import com.atguigu.gmall.model.enums.ProcessStatus;
import com.atguigu.gmall.model.order.OrderInfo;
import com.atguigu.gmall.order.service.OrderService;
import com.rabbitmq.client.Channel;
import lombok.SneakyThrows;
import org.apache.commons.lang.StringUtils;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * @author mqx
 * @date 2020/3/31 15:18
 */
@Component
public class OrderReceiver {

    @Autowired
    private OrderService orderService;
    // 获取监听到的消息队列
    @SneakyThrows
    @RabbitListener(queues = MqConst.QUEUE_ORDER_CANCEL)
    public void orderCancel(Long orderId , Message message, Channel channel){
        if (orderId!=null){
            // 那么如果当前的订单已经付完款了。
            // 判断当前订单的状态！当前订单状态是为付款，order_status UNPAID 这个时候才关闭订单！
            // 先查询一下当前的订单！
            // 扩展：判断当前订单是否真正的支付{在支付宝中是否有交易记录。双保险！}
            OrderInfo orderInfo = orderService.getById(orderId);
            if (orderInfo!=null && orderInfo.getOrderStatus().equals(ProcessStatus.UNPAID.getOrderStatus().name())){
                // 修改订单的状态！订单的状态变成CLOSED
                orderService.execExpiredOrder(orderId);
            }
        }
        // 手动ack
        channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);
    }



    //监听支付完成之后的消息队列
    @SneakyThrows
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConst.QUEUE_PAYMENT_PAY,durable = "true"),
            exchange = @Exchange(value = MqConst.EXCHANGE_DIRECT_PAYMENT_PAY),
            key = {MqConst.ROUTING_PAYMENT_PAY}
    ))
    public void paySuccess(Long orderId,Message message,Channel channel){
        //判断当前orderId 不能为空
        if(null !=orderId){
            //更新订单的状态 再次确认订单状态
            OrderInfo orderInfo = orderService.getById(orderId);
            //判断orderInfo中当前记录 订单状态未付款
            if(null != orderInfo && orderInfo.getOrderStatus().equals(ProcessStatus.UNPAID.getOrderStatus().name())){
                orderService.updateOrderStatus(orderId,ProcessStatus.PAID);
                //发送消息 通知仓库
                orderService.sendOrderStatus(orderId);
            }
        }
        //手动确认消息处理完毕
        channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);
    }

    @SneakyThrows
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConst.QUEUE_WARE_ORDER,durable = "true"),
            exchange = @Exchange(value = MqConst.EXCHANGE_DIRECT_WARE_ORDER),
            key = {MqConst.ROUTING_WARE_ORDER}
    ))
    public void updateOrderStatus(String msgJson,Message message,Channel channel){
        //判断当前的消息不能为空
        if(!StringUtils.isEmpty(msgJson)){
            //json转化map
            Map map = JSON.parseObject(msgJson, Map.class);
            String orderId = (String) map.get("orderId");
            String status = (String) map.get("status");
            //判断减库存结果
            if("DEDUCTED".equals(status)){
                //减库存成功
                orderService.updateOrderStatus(Long.parseLong(orderId),ProcessStatus.WAITING_DELEVER);//代发货

            }else {
                orderService.updateOrderStatus(Long.parseLong(orderId),ProcessStatus.STOCK_EXCEPTION);//库存异常


            }
        }
        channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);
    }
}
