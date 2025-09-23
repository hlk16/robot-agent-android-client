package com.lhht.xiaozhi.services;

/**
 * PID控制器类
 * 用于计算基于误差的控制输出
 */
public class PIDController {
    
    // PID参数
    private double kp; // 比例系数
    private double ki; // 积分系数
    private double kd; // 微分系数
    
    // 控制变量
    private double previousError = 0.0;
    private double integral = 0.0;
    private double setpoint = 0.0;
    
    // 积分限幅
    private double integralMax = 100.0;
    private double integralMin = -100.0;
    
    // 输出限幅
    private double outputMax = 100.0;
    private double outputMin = -100.0;
    
    /**
     * 构造函数
     * @param kp 比例系数
     * @param ki 积分系数
     * @param kd 微分系数
     */
    public PIDController(double kp, double ki, double kd) {
        this.kp = kp;
        this.ki = ki;
        this.kd = kd;
    }
    
    /**
     * 计算PID输出
     * @param currentValue 当前值
     * @return PID控制输出
     */
    public double calculate(double currentValue) {
        // 计算误差
        double error = setpoint - currentValue;
        
        // 积分项
        integral += error;
        
        // 积分限幅
        if (integral > integralMax) {
            integral = integralMax;
        } else if (integral < integralMin) {
            integral = integralMin;
        }
        
        // 微分项
        double derivative = error - previousError;
        
        // PID输出计算
        double output = kp * error + ki * integral + kd * derivative;
        
        // 输出限幅
        if (output > outputMax) {
            output = outputMax;
        } else if (output < outputMin) {
            output = outputMin;
        }
        
        // 保存当前误差用于下次计算
        previousError = error;
        
        return output;
    }
    
    /**
     * 重置PID控制器
     */
    public void reset() {
        previousError = 0.0;
        integral = 0.0;
    }
    
    /**
     * 设置PID参数
     * @param kp 比例系数
     * @param ki 积分系数
     * @param kd 微分系数
     */
    public void setPIDParameters(double kp, double ki, double kd) {
        this.kp = kp;
        this.ki = ki;
        this.kd = kd;
    }
    
    /**
     * 设置目标值
     * @param setpoint 目标值
     */
    public void setSetpoint(double setpoint) {
        this.setpoint = setpoint;
    }
    
    /**
     * 设置积分限幅
     * @param min 最小值
     * @param max 最大值
     */
    public void setIntegralLimits(double min, double max) {
        this.integralMin = min;
        this.integralMax = max;
    }
    
    /**
     * 设置输出限幅
     * @param min 最小值
     * @param max 最大值
     */
    public void setOutputLimits(double min, double max) {
        this.outputMin = min;
        this.outputMax = max;
    }
    
    // Getter方法
    public double getKp() { return kp; }
    public double getKi() { return ki; }
    public double getKd() { return kd; }
    public double getSetpoint() { return setpoint; }
    public double getPreviousError() { return previousError; }
    public double getIntegral() { return integral; }
}