package cn.javayong.shardingjdbc5.spring.service;

import cn.javayong.shardingjdbc5.spring.domain.mapper.OrderMapper;
import cn.javayong.shardingjdbc5.spring.domain.po.TEntOrder;
import cn.javayong.shardingjdbc5.spring.domain.po.TEntOrderDetail;
import cn.javayong.shardingjdbc5.spring.domain.po.TEntOrderItem;
import org.apache.commons.lang3.RandomUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class OrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private RedisIdGeneratorService redisIdGeneratorService;

    public TEntOrder getOrderById(Long id) {
        return orderMapper.getOrderById(id);
    }

    public Map<String, Object> queryOrder(Long orderId) {
        return orderMapper.queryOrder(orderId);
    }

    public List<Map<String, Object>> queryOrderList(Integer page) {
        return orderMapper.queryOrderList(page);
    }

    public Integer queryOrderCount() {
        return orderMapper.queryOrderCount();
    }

    @Transactional
    public Long save() {
        Long entId = 11215L;
        String regionCode = "BJ";
        Date createTime = new Date();
        //保存订单基本信息
        TEntOrder tEntOrder = new TEntOrder();
        Long orderId = redisIdGeneratorService.createUniqueId(String.valueOf(entId));
        tEntOrder.setId(orderId);
        tEntOrder.setRegionCode(regionCode);
        tEntOrder.setAmount(new BigDecimal(12.0));
        tEntOrder.setMobile("150****9235");
        tEntOrder.setEntId(entId);
        tEntOrder.setCreateTime(createTime);
        tEntOrder.setUpdateTime(createTime);
        orderMapper.saveOrder(tEntOrder);

        //保存订单详情
        TEntOrderDetail tEntOrderDetail = new TEntOrderDetail();
        Long detailId = redisIdGeneratorService.createUniqueId(String.valueOf(entId));
        tEntOrderDetail.setId(detailId);
        tEntOrderDetail.setAddress("湖北武汉东西湖区");
        tEntOrderDetail.setOrderId(orderId);
        tEntOrderDetail.setEntId(entId);
        tEntOrderDetail.setStatus(1);
        tEntOrderDetail.setRegionCode(regionCode);
        tEntOrderDetail.setCreateTime(createTime);
        tEntOrderDetail.setUpdateTime(createTime);
        orderMapper.saveOrderDetail(tEntOrderDetail);

        //保存订单条目表
        {
            //保存条目 1
            TEntOrderItem item1 = new TEntOrderItem();
            Long itemId = redisIdGeneratorService.createUniqueId(String.valueOf(entId));
            item1.setId(itemId);
            item1.setEntId(entId);
            item1.setOrderId(orderId);
            item1.setRegionCode("BG");
            item1.setGoodId("aaaaaaaaaaaa");
            item1.setGoodName("我的商品111111");
            item1.setCreateTime(createTime);
            item1.setUpdateTime(createTime);
            orderMapper.saveOrderItem(item1);
            //保存条目 2
            TEntOrderItem item2 = new TEntOrderItem();
            Long itemId2 = redisIdGeneratorService.createUniqueId(String.valueOf(entId));
            item2.setId(itemId2);
            item2.setEntId(entId);
            item2.setRegionCode("BJ");
            item2.setOrderId(orderId);
            item2.setGoodId("bbbbbbbbbbbb");
            item2.setGoodName("我的商品22222");
            item2.setCreateTime(createTime);
            item2.setUpdateTime(createTime);
            orderMapper.saveOrderItem(item2);
        }
        return orderId;
    }

    public void batchsave() throws InterruptedException {
        for (int i = 0; i < 100000; i++) {
            Long entId = RandomUtils.nextLong();
            String regionCode = "BJ";
            Date createTime = new Date();
            //保存订单基本信息
            TEntOrder tEntOrder = new TEntOrder();
            Long orderId = redisIdGeneratorService.createUniqueId(String.valueOf(entId));
            tEntOrder.setId(orderId);
            tEntOrder.setRegionCode(regionCode);
            tEntOrder.setAmount(new BigDecimal(12.0));
            tEntOrder.setMobile("150****9235");
            tEntOrder.setEntId(entId);
            tEntOrder.setCreateTime(createTime);
            tEntOrder.setUpdateTime(createTime);
            orderMapper.saveOrder(tEntOrder);

            //保存订单详情
            TEntOrderDetail tEntOrderDetail = new TEntOrderDetail();
            Long detailId = redisIdGeneratorService.createUniqueId(String.valueOf(entId));
            tEntOrderDetail.setId(detailId);
            tEntOrderDetail.setAddress("湖北武汉东西湖区");
            tEntOrderDetail.setOrderId(orderId);
            tEntOrderDetail.setEntId(entId);
            tEntOrderDetail.setStatus(1);
            tEntOrderDetail.setRegionCode(regionCode);
            tEntOrderDetail.setCreateTime(createTime);
            tEntOrderDetail.setUpdateTime(createTime);
            orderMapper.saveOrderDetail(tEntOrderDetail);

            //保存订单条目表
            {
                //保存条目 1
                TEntOrderItem item1 = new TEntOrderItem();
                Long itemId = redisIdGeneratorService.createUniqueId(String.valueOf(entId));
                item1.setId(itemId);
                item1.setEntId(entId);
                item1.setOrderId(orderId);
                item1.setRegionCode("BG");
                item1.setGoodId("aaaaaaaaaaaa");
                item1.setGoodName("我的商品111111");
                item1.setCreateTime(createTime);
                item1.setUpdateTime(createTime);
                orderMapper.saveOrderItem(item1);
                //保存条目 2
                TEntOrderItem item2 = new TEntOrderItem();
                Long itemId2 = redisIdGeneratorService.createUniqueId(String.valueOf(entId));
                item2.setId(itemId2);
                item2.setEntId(entId);
                item2.setRegionCode("BJ");
                item2.setOrderId(orderId);
                item2.setGoodId("bbbbbbbbbbbb");
                item2.setGoodName("我的商品22222");
                item2.setCreateTime(createTime);
                item2.setUpdateTime(createTime);
                orderMapper.saveOrderItem(item2);
            }
            Thread.sleep(500 * new Random().nextInt(10));
        }
    }

    /**
     * 删除指定订单号的订单、订单详情和订单项
     * @param orderId 订单号
     */
    @Transactional
    public void deleteOrder(Long orderId) {
        // 删除订单条目表
        orderMapper.deleteOrderItemByOrderId(orderId);
        // 删除订单详情
        orderMapper.deleteOrderDetailByOrderId(orderId);
        // 删除订单基本信息
        orderMapper.deleteOrderById(orderId);
    }

    public List queryOrderListDemo() {
        return orderMapper.queryOrderListDemo();
    }

    // 查询城市列表
    public List queryCityList() {
        return orderMapper.queryCityList();
    }

    public Integer updateCity() {
        return orderMapper.updateCity();
    }

}
