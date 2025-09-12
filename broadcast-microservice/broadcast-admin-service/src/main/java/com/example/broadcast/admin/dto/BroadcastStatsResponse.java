package com.example.broadcast.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data 
@Builder 
@NoArgsConstructor 
@AllArgsConstructor
public class BroadcastStatsResponse {
    private Long broadcastId;
    private Integer totalTargeted;
    private Integer totalDelivered;
    private Integer totalRead;
    private double deliveryRate;
    private double readRate;
}