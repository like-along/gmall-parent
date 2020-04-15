package com.atguigu.gmall.activity.service;

import com.atguigu.gmall.common.result.Result;
import com.atguigu.gmall.model.activity.SeckillGoods;

import java.util.List;

/**
 * @author DL
 * @create 2020-04-06 11:28
 */
public interface SeckillGoodsService {

    //所有秒杀商品
    List<SeckillGoods> findAll();

    //根据id获取数据
    SeckillGoods getSeckillGoodsById(Long id);

    /**
     * 预下单
     * @param skuId
     * @param userId
     */
    void seckillOrder(Long skuId, String userId);

    /**
     * 检查订单接口
     * @param skuId
     * @param userId
     * @return
     */
    Result checkOrder(Long skuId, String userId);
}
