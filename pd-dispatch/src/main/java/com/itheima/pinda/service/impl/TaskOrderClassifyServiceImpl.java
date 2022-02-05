package com.itheima.pinda.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.collect.Maps;
import com.itheima.pinda.DTO.OrderClassifyDTO;
import com.itheima.pinda.DTO.OrderClassifyGroupDTO;
import com.itheima.pinda.DTO.OrderSearchDTO;
import com.itheima.pinda.DTO.angency.AgencyScopeDto;
import com.itheima.pinda.authority.api.AreaApi;
import com.itheima.pinda.authority.entity.common.Area;
import com.itheima.pinda.base.R;
import com.itheima.pinda.common.utils.EntCoordSyncJob;
import com.itheima.pinda.common.utils.Result;
import com.itheima.pinda.entity.Order;
import com.itheima.pinda.entity.OrderClassifyEntity;
import com.itheima.pinda.entity.OrderClassifyOrderEntity;
import com.itheima.pinda.enums.OrderStatus;
import com.itheima.pinda.feign.OrderFeign;
import com.itheima.pinda.feign.agency.AgencyScopeFeign;
import com.itheima.pinda.future.PdCompletableFuture;
import com.itheima.pinda.service.IOrderClassifyOrderService;
import com.itheima.pinda.service.IOrderClassifyService;
import com.itheima.pinda.service.ITaskOrderClassifyService;
import com.itheima.pinda.utils.IdUtils;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 订单分类实现类
 */
@Service
@Slf4j
public class TaskOrderClassifyServiceImpl implements ITaskOrderClassifyService {
    @Autowired
    private OrderFeign orderFeign;
    @Autowired
    private AgencyScopeFeign agencyScopeFeign;
    @Autowired
    private IOrderClassifyService orderClassifyService;
    @Autowired
    private IOrderClassifyOrderService orderClassifyOrderService;

    /**
     * 订单分类核心逻辑
     *
     * @param agencyId 机构id（网点或者转运中心的id）
     * @param jobId    定时任务id
     * @param logId    日志id
     * @return
     */
    @Override
    public List<OrderClassifyGroupDTO> execute(String agencyId, String jobId, String logId) {
        //当前集合用于存放当前机构的订单
        List<OrderClassifyDTO> orderClassifyDTOS = new ArrayList<>();
        //当前集合用于存放分类之后的订单信息
        List<OrderClassifyGroupDTO> orderClassifyGroupDTOS = new ArrayList<>();

        // 将新订单放入集合中
        orderClassifyDTOS.addAll(buildNewOrder(agencyId));
        // 将中转订单放入集合中
        orderClassifyDTOS.addAll(buildTransferOrder(agencyId));
        // 订单分组  OrderClassifyDTO::groupBy 可自定义分组维度
        Map<String, List<OrderClassifyDTO>> orderClassifyDTOGroup = orderClassifyDTOS.stream().collect(Collectors.groupingBy(OrderClassifyDTO::groupBy));
        // 分组后的订单装在如下实体
        OrderClassifyGroupDTO.OrderClassifyGroupDTOBuilder groupBuilder = OrderClassifyGroupDTO.builder();
        orderClassifyDTOGroup.forEach((key, value) -> {
            groupBuilder.key(key);
            groupBuilder.orders(value.stream().map(OrderClassifyDTO::getOrder).collect(Collectors.toList()));
            orderClassifyGroupDTOS.add(groupBuilder.build());
        });
        //保存订单分类后的数据
        saveRecord(orderClassifyGroupDTOS, jobId, logId);
        return orderClassifyGroupDTOS;
    }

    /**
     * 保存订单分类结果
     *
     * @param orderClassifyGroupDTOS
     * @param jobId
     * @param logId
     */
    private void saveRecord(List<OrderClassifyGroupDTO> orderClassifyGroupDTOS, String jobId, String logId) {
        orderClassifyGroupDTOS.forEach(item -> {
            if (item.isNew()) {
                log.info("新订单 保存分组信息");
                OrderClassifyEntity entity = new OrderClassifyEntity();
                entity.setClassify(item.getKey());
                entity.setJobId(jobId);
                entity.setJobLogId(logId);
                if (!entity.getClassify().equals("ERROR")) {
                    entity.setStartAgencyId(item.getStartAgencyId());
                    entity.setEndAgencyId(item.getEndAgencyId());
                }
                entity.setTotal(item.getOrders().size());
                entity.setId(IdUtils.get());
                List<OrderClassifyOrderEntity> orderClassifyOrders = item.getOrders().stream().map((order) -> {
                    OrderClassifyOrderEntity orderClassifyOrderEntity = new OrderClassifyOrderEntity();
                    orderClassifyOrderEntity.setOrderId(order.getId());
                    orderClassifyOrderEntity.setOrderClassifyId(entity.getId());
                    orderClassifyOrderEntity.setId(IdUtils.get());
                    return orderClassifyOrderEntity;
                }).collect(Collectors.toList());
                item.setId(entity.getId());
                orderClassifyService.save(entity);
                orderClassifyOrderService.saveBatch(orderClassifyOrders);
            } else {
                log.info("中转订单，查询分组信息");
                List<String> orderIds = item.getOrders().stream().map(Order::getId).collect(Collectors.toList());
                log.info("当前分组的订单id：{}", orderIds);
                LambdaQueryWrapper<OrderClassifyOrderEntity> wrapper = new LambdaQueryWrapper<>();
                wrapper.in(OrderClassifyOrderEntity::getOrderId, orderIds);
                List<OrderClassifyOrderEntity> orderClassifyOrders = orderClassifyOrderService.list(wrapper);
                // 不出意外只会查到一个订单分类id
                Set<String> orderClassifyIds = orderClassifyOrders.stream().map(OrderClassifyOrderEntity::getOrderClassifyId).collect(Collectors.toSet());
                log.info("查询订单分组id:{}", orderClassifyIds);
                if (CollectionUtils.isEmpty(orderClassifyIds)) {
                    log.error("中转订单异常:{}", orderIds);
                    return;
                }
                item.setId(orderClassifyIds.iterator().next());
            }
        });
    }

    /**
     * 查询中转订单
     *
     * @param agencyId
     * @return
     */
    private List<OrderClassifyDTO> buildTransferOrder(String agencyId) {
        OrderSearchDTO orderSearchDTO = new OrderSearchDTO();
        //订单状态为运输中
        orderSearchDTO.setStatus(OrderStatus.IN_TRANSIT.getCode());
        //订单当前所在机构
        orderSearchDTO.setCurrentAgencyId(agencyId);
        List<Order> orders = orderFeign.list(orderSearchDTO);
        log.info("查询[运输中]状态订单：{} 条", orders.size());
        OrderClassifyDTO.OrderClassifyDTOBuilder builder = OrderClassifyDTO.builder();
        builder.currentAgencyId(agencyId);
        List<OrderClassifyDTO> orderClassifyDTOS = orders.stream().map(item -> {
            builder.startAgencyId(getStartAgencyId(item));
            builder.endAgencyId(getEndAgencyId(item));
            builder.orderType(item.getOrderType());
            builder.order(item);
            return builder.build();
        }).collect(Collectors.toList());
        log.info("订单分类：中转订单{}条", orderClassifyDTOS.size());
        return orderClassifyDTOS;
    }

    /**
     * 根据订单获得起始机构id
     *
     * @param order
     * @return
     */
    private String getStartAgencyId(Order order) {
        String address = senderFullAddress(order);
        if (StringUtils.isBlank(address)) {
            exceptionHappend("下单时发货地址不能为空");
        }
        String location = EntCoordSyncJob.getCoordinate(address);
        log.info("订单发货地址和坐标-->" + address + "--" + location);
        if (StringUtils.isBlank(location)) {
            exceptionHappend("下单时发货地址不能为空");
        }
        //根据坐标获取区域检查区域是否正常
        Map map = EntCoordSyncJob.getLocationByPosition(location);
        if (ObjectUtils.isEmpty(map)) {
            exceptionHappend("根据地图获取区域信息为空");
        }
        String adcode = map.getOrDefault("adcode", "").toString();
        R<Area> r = areaApi.getByCode(adcode + "000000");
        if (!r.getIsSuccess()) {
            Result.error(r.getMsg());
        }
        Area area = r.getData();
        if (area == null) {
            exceptionHappend("区域编码:" + adcode + "区域信息未从库中获取到");
        }
        Long areaId = area.getId();
        if (!order.getSenderCountyId().equals(String.valueOf(areaId))) {
            exceptionHappend("参数中发货区域id和坐标计算出真实区域id不同，数据不合法");
        }
        List<AgencyScopeDto> agencyScopes = agencyScopeFeign.findAllAgencyScope(areaId + "", null, null, null);
        if (agencyScopes == null || agencyScopes.size() == 0) {
            exceptionHappend("根据区域无法从机构范围获取网点信息列表");
        }
        Result res = calcuate(location, agencyScopes);
        return res.get("agencyId").toString();
    }

    /**
     * 获得发件人详细地址信息
     *
     * @param order
     * @return
     */
    @SneakyThrows
    private String senderFullAddress(Order order) {
        StringBuffer stringBuffer = new StringBuffer();
        Long province = Long.valueOf(order.getSenderProvinceId());
        Long city = Long.valueOf(order.getSenderCityId());
        Long county = Long.valueOf(order.getSenderCountyId());
        Set areaIdSet = new HashSet();
        areaIdSet.add(province);
        areaIdSet.add(city);
        areaIdSet.add(county);
        CompletableFuture<Map<Long, Area>> areaMapFuture = PdCompletableFuture.areaMapFuture(areaApi, null, areaIdSet);
        Map<Long, Area> areaMap = areaMapFuture.get();
        stringBuffer.append(areaMap.get(province).getName());
        stringBuffer.append(areaMap.get(city).getName());
        stringBuffer.append(areaMap.get(county).getName());
        stringBuffer.append(order.getSenderAddress());
        return stringBuffer.toString();
    }

    /**
     * 获取指定机构下的新订单
     *
     * @param agencyId
     * @return
     */
    private List<OrderClassifyDTO> buildNewOrder(String agencyId) {
        OrderSearchDTO orderSearchDTO = new OrderSearchDTO();
        //订单状态为网点入库
        orderSearchDTO.setStatus(OrderStatus.OUTLETS_WAREHOUSE.getCode());
        //订单当前所在机构
        orderSearchDTO.setCurrentAgencyId(agencyId);
        List<Order> orders = orderFeign.list(orderSearchDTO);
        log.info("查询[网点入库]状态订单：{} 条", orders.size());
        OrderClassifyDTO.OrderClassifyDTOBuilder builder = OrderClassifyDTO.builder();
        builder.currentAgencyId(agencyId);
        List<OrderClassifyDTO> orderClassifyDTOS = orders.stream().map(item -> {
            //起始机构
            builder.startAgencyId(agencyId);
            //目的地机构（网点）
            builder.endAgencyId(getEndAgencyId(item));
            builder.orderType(item.getOrderType());
            builder.order(item);
            return builder.build();
        }).collect(Collectors.toList());
        log.info("订单分类：首次发出订单{}条", orderClassifyDTOS.size());
        return orderClassifyDTOS;
    }

    /**
     * 抛出异常
     *
     * @param msg
     */
    private void exceptionHappend(String msg) {
        try {
            throw new Exception(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 获取目的地网点id
     *
     * @param order
     * @return
     */
    private String getEndAgencyId(Order order) {
        //获得收件人详细地址（包含省市区）
        String address = receiverFullAddress(order);
        if (StringUtils.isBlank(address)) {
            exceptionHappend("下单时收货地址不能为空");
        }
        //调用百度地图工具类，根据地址获取对应经纬度坐标
        String location = EntCoordSyncJob.getCoordinate(address);
        log.info("订单收货地址和坐标-->" + address + "--" + location);
        if (StringUtils.isBlank(location)) {
            exceptionHappend("下单时收货地址不能正确");
        }
        //根据坐标获取区域检查区域是否正常
        Map map = EntCoordSyncJob.getLocationByPosition(location);
        if (ObjectUtils.isEmpty(map)) {
            exceptionHappend("根据地图获取区域信息为空");
        }
        String adcode = map.getOrDefault("adcode", "").toString();
        R<Area> r = areaApi.getByCode(adcode + "000000");
        Area area = r.getData();
        if (area == null) {
            exceptionHappend("区域编码:" + adcode + "区域信息未从库中获取到");
        }
        Long areaId = area.getId();
        if (!order.getReceiverCountyId().equals(String.valueOf(areaId))) {
            exceptionHappend("参数中收货区域id和坐标计算出真实区域id不同，数据不合法");
        }
        List<AgencyScopeDto> agencyScopes = agencyScopeFeign.findAllAgencyScope(areaId + "", null, null, null);
        if (agencyScopes == null || agencyScopes.size() == 0) {
            exceptionHappend("根据区域无法从机构范围获取网点信息列表");
        }

        //计算距离最近的网点
        Result res = caculate(location, agencyScopes);
        return res.get("agencyId").toString();
    }

    /**
     * 从给定网点中查找覆盖指定点的网点
     *
     * @param agencyScopes
     * @param location
     * @return
     */
    private Result caculate(List<AgencyScopeDto> agencyScopes, String location) {
        try {
            for (AgencyScopeDto agencyScopeDto : agencyScopes) {
                List<List<Map>> mutiPoints = agencyScopeDto.getMutiPoints();
                for (List<Map> list : mutiPoints) {
                    String[] originArray = location.split(",");
                    boolean flag = EntCoordSyncJob.isInScope(list, Double.parseDouble(originArray[0]), Double.parseDouble(originArray[1]));
                    if (flag) {
                        return Result.ok().put("agencyId", agencyScopeDto.getAgencyId());
                    }
                }
            }
        } catch (Exception e) {
            log.error("获取所属网点异常", e);
            return Result.error(5000, "获取所属网点失败");
        }
        return Result.error(5000, "获取所属网点失败");
    }

    /**
     * 获取坐标值
     *
     * @param map
     * @return
     */
    private String getPoint(Map map) {
        String lng = map.get("lng").toString();
        String lat = map.get("lat").toString();
        return lng + "," + lat;
    }

    public static void main(String[] args) {
        /*String address = "北京市昌平区建材城西路金燕龙办公楼";
        String location = EntCoordSyncJob.getCoordinate(address);

        //调用百度地图，根据经纬度获取区域信息
        Map map = EntCoordSyncJob.getLocationByPosition(location);

        System.out.println(location);
        System.out.println("----");
        System.out.println(map);

        String adcode = (String) map.get("adcode");
        System.out.println(adcode);*/

        /*List<AgencyScopeDto> agencyScopeList = new ArrayList<>();
        AgencyScopeDto dto1 = new AgencyScopeDto();
        dto1.setAgencyId("1");
        List<List<Map>> points1 = new ArrayList<>();
        List<Map> points1_1 = new ArrayList<>();
        Map point1 = new HashMap();
        point1.put("lng",116.337566);
        point1.put("lat",40.067944);
        Map point2 = new HashMap();
        point2.put("lng",116.362215);
        point2.put("lat",40.0741);
        points1_1.add(point1);
        points1_1.add(point2);
        points1.add(points1_1);
        dto1.setMutiPoints(points1);

        AgencyScopeDto dto2 = new AgencyScopeDto();
        dto2.setAgencyId("2");
        List<List<Map>> points2 = new ArrayList<>();
        List<Map> points2_1 = new ArrayList<>();
        Map point3 = new HashMap();
        point3.put("lng",116.3344);
        point3.put("lat",40.067);
        Map point4 = new HashMap();
        point4.put("lng",116.311215);
        point4.put("lat",40.10741);
        points2_1.add(point3);
        points2_1.add(point4);
        points2.add(points2_1);
        dto2.setMutiPoints(points2);


        agencyScopeList.add(dto1);
        agencyScopeList.add(dto2);
        String location = "116.349936,40.066258";
        new TaskOrderClassifyServiceImpl().caculate(agencyScopeList,location);*/


        List<OrderClassifyDTO> orderClassifyDTOS = new ArrayList<>();
        List<OrderClassifyGroupDTO> orderClassifyGroupDTOS = new ArrayList<>();

        OrderClassifyDTO.OrderClassifyDTOBuilder builder1 = OrderClassifyDTO.builder();
        builder1.startAgencyId("1");
        builder1.endAgencyId("10");
        builder1.currentAgencyId("1");
        Order order = new Order();
        order.setId("10001");
        builder1.order(order);
        OrderClassifyDTO dto1 = builder1.build();
        orderClassifyDTOS.add(dto1);

        OrderClassifyDTO.OrderClassifyDTOBuilder builder2 = OrderClassifyDTO.builder();
        builder2.startAgencyId("1");
        builder2.endAgencyId("10");
        builder2.currentAgencyId("1");
        Order order2 = new Order();
        order2.setId("10002");
        builder2.order(order2);
        OrderClassifyDTO dto2 = builder2.build();
        orderClassifyDTOS.add(dto2);

        OrderClassifyDTO.OrderClassifyDTOBuilder builder3 = OrderClassifyDTO.builder();
        builder3.startAgencyId("2");
        builder3.endAgencyId("12");
        builder3.currentAgencyId("11");
        Order order3 = new Order();
        order3.setId("10003");
        builder3.order(order3);
        OrderClassifyDTO dto3 = builder3.build();
        orderClassifyDTOS.add(dto3);

        Map<String, List<OrderClassifyDTO>> orderClassifyDTOGroup = orderClassifyDTOS.stream().collect(Collectors.groupingBy(OrderClassifyDTO::groupBy));

        OrderClassifyGroupDTO.OrderClassifyGroupDTOBuilder builder = OrderClassifyGroupDTO.builder();

        //进行对象转换，将当前Map对象转为 List<OrderClassifyGroupDTO>类型
        orderClassifyDTOGroup.forEach((key, value) -> {
            builder.key(key);
            //获取原始订单对象
            List<Order> orders = value.stream().map((item) -> item.getOrder()).collect(Collectors.toList());
            builder.orders(orders);
            OrderClassifyGroupDTO orderClassifyGroupDTO = builder.build();
            orderClassifyGroupDTOS.add(orderClassifyGroupDTO);
        });

        System.out.println(orderClassifyGroupDTOS);
    }

    @Autowired
    private AreaApi areaApi;

    /**
     * 根据订单获取对应的完整收件人地址信息
     *
     * @param order
     * @return
     */
    @SneakyThrows
    private String receiverFullAddress(Order order) {
        StringBuffer stringBuffer = new StringBuffer();
        Long province = Long.valueOf(order.getReceiverProvinceId());
        Long city = Long.valueOf(order.getReceiverCityId());
        Long county = Long.valueOf(order.getReceiverCountyId());
        Set areaIdSet = new HashSet();
        areaIdSet.add(province);
        areaIdSet.add(city);
        areaIdSet.add(county);
        CompletableFuture<Map<Long, Area>> areaMapFuture = PdCompletableFuture.areaMapFuture(areaApi, null, areaIdSet);
        Map<Long, Area> areaMap = areaMapFuture.get();
        stringBuffer.append(areaMap.get(province).getName());
        stringBuffer.append(areaMap.get(city).getName());
        stringBuffer.append(areaMap.get(county).getName());
        stringBuffer.append(order.getReceiverAddress());
        return stringBuffer.toString();
    }
}
