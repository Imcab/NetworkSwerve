package frc.robot.DriveTrain;

import com.ctre.phoenix6.hardware.CANcoder;
import com.revrobotics.RelativeEncoder;
import com.revrobotics.spark.SparkBase.PersistMode;
import com.revrobotics.spark.SparkLowLevel.MotorType;
import com.revrobotics.spark.SparkMax;
import com.revrobotics.spark.config.SparkBaseConfig.IdleMode;
import com.revrobotics.spark.config.SparkMaxConfig;

import edu.wpi.first.math.MathUtil;
import edu.wpi.first.math.controller.PIDController;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.SwerveModulePosition;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.math.system.plant.DCMotor;
import edu.wpi.first.math.system.plant.LinearSystemId;
import edu.wpi.first.math.util.Units;
import edu.wpi.first.wpilibj.simulation.DCMotorSim;
import lib.CommandBase.Sim.RealDevice;
import lib.CommandBase.Sim.SimulatedDevice;
import lib.CommandBase.Sim.SimulatedSubsystem;
import lib.Constants.ProfileGains.PIDGains;
import lib.Constants.ProfileGains.SimpleFeedForwardGains;

public class SwerveModule implements SimulatedSubsystem{

    public static final int driveCurrentLimit = 50;
    public static final int turnCurrentLimit = 20;
    public static final DCMotor NEOGearbox = DCMotor.getNEO(1);
    public static final double WHEELRADIUS = Units.inchesToMeters(2.0);
    public static final double driveMotorReduction = 5.36;
    public static final double turnMotorReduction =  18.75;

    @RealDevice
    private SparkMax driveSparkMax;
    @RealDevice
    private SparkMax turnSparkMax;
    @RealDevice
    private CANcoder absoluteEncoder;

    @RealDevice
    private RelativeEncoder enc_drive;
    @RealDevice
    private RelativeEncoder enc_turn;

    @RealDevice
    private SparkMaxConfig config_drive = new SparkMaxConfig();
    @RealDevice
    private SparkMaxConfig config_turn = new SparkMaxConfig();

    @SimulatedDevice
    private DCMotorSim driveSim =
    new DCMotorSim(
        LinearSystemId.createDCMotorSystem(NEOGearbox, 0.025, driveMotorReduction),
        NEOGearbox);
    
    @SimulatedDevice
    private DCMotorSim turnSim =
    new DCMotorSim(
        LinearSystemId.createDCMotorSystem(NEOGearbox, 0.004, turnMotorReduction),
        NEOGearbox);

    private final PIDController drivePID;
    private final PIDController turnPID;

    private final SimpleFeedForwardGains driveFFGains;
    private final PIDGains drivePIDGains;
    private final PIDGains turnPIDGains;

    private Rotation2d angleSetpoint = null;
    private Double speedSetpoint = null;

    private double ffVolts = 0;

    private double driveVelocity;

    private double driveVoltage;
    private double turnVoltage;

    private double Offset;

    private boolean isDriveMotorInverted;
    private boolean isTurnMotorInverted;

    private Rotation2d moduleAngle = new Rotation2d();
    
    public SwerveModule(int index){
   
        //Use real Configuration
        if (!isInSimulation()) {
            this.turnPIDGains = new PIDGains(5.0, 0,0);
            this.drivePIDGains = new PIDGains(0.05, 0, 0);
            this.driveFFGains = new SimpleFeedForwardGains(0.1, 0.08, 0);
            
            createSparks(index);

        }else{
        //Use sim Configuration
            this.turnPIDGains = new PIDGains(8.0, 0,0);
            this.drivePIDGains = new PIDGains(0.05, 0, 0);
            this.driveFFGains = new SimpleFeedForwardGains(0, 0.0789, 0);
        }

        drivePID = new PIDController(drivePIDGains.kP(), drivePIDGains.kI(), drivePIDGains.kD());
        turnPID = new PIDController(turnPIDGains.kP(), turnPIDGains.kI(),turnPIDGains.kD());

        turnPID.enableContinuousInput(-Math.PI, Math.PI);

        initializeSubsystemDevices();

    }

    //Main Loop
    public void periodic(){
        handleSubsystemRealityLoop();

        if (angleSetpoint != null) {
            this.turnVoltage = turnPID.calculate(moduleAngle.getRadians());

            if (speedSetpoint != null) {
                this.driveVoltage = ffVolts + drivePID.calculate(driveVelocity);
            }else{
                this.driveVoltage = 0;
                drivePID.reset();
            }
        }else{
            this.turnVoltage = 0;
        }
    }

    @Override
    public void SimulationDevicesPeriodic(){

        this.moduleAngle = new Rotation2d(turnSim.getAngularPositionRad());
        this.driveVelocity = driveSim.getAngularVelocityRadPerSec();

        driveSim.setInputVoltage(MathUtil.clamp(driveVoltage, -12.0, 12.0));
        turnSim.setInputVoltage(MathUtil.clamp(turnVoltage, -12.0, 12.0));

        driveSim.update(0.02);
        turnSim.update(0.02);
    }

    @Override
    public void RealDevicesPeriodic(){

        this.moduleAngle = 
        absoluteEncoder.isConnected() ? 
            Rotation2d.fromRotations(absoluteEncoder.getAbsolutePosition().getValueAsDouble() - Offset) : 
            Rotation2d.fromRotations(enc_turn.getPosition() / turnMotorReduction);

        this.driveVelocity = Units.rotationsPerMinuteToRadiansPerSecond(enc_drive.getVelocity());
    }

    public void setDriveVelocity(double velocity){
        this.ffVolts = driveFFGains.kS() * Math.signum(velocity) + driveFFGains.kV() * velocity;
        drivePID.setSetpoint(velocity);
    }

    public void setTurnPos(Rotation2d rot){
        turnPID.setSetpoint(rot.getRadians());
    }

    public double getDriveModuleVoltage(){
        return driveVoltage;
    }

    public double getTurnModuleVoltage(){
        return turnVoltage;
    }

    public double getModuleVelocity(){
        return driveVelocity * WHEELRADIUS;
    }

    public double getDrivePositionMeters(){

        double position = isInSimulation() ? driveSim.getAngularPositionRad() : Units.rotationsToRadians(enc_drive.getPosition());

        return position * WHEELRADIUS;
      }

    public Rotation2d getModuleRotation(){
        return moduleAngle;
    }

    public void runSetpoint(SwerveModuleState desiredState){
        desiredState.optimize(getModuleRotation());

        desiredState.cosineScale(getModuleRotation());

        angleSetpoint = desiredState.angle;
        speedSetpoint = desiredState.speedMetersPerSecond;

        setTurnPos(angleSetpoint);
        setDriveVelocity(speedSetpoint / WHEELRADIUS);

    }

    public void toHome(){
        runSetpoint(new SwerveModuleState(0, new Rotation2d()));
    }

    public SwerveModulePosition getPosition(){
        return new SwerveModulePosition(getDrivePositionMeters(), getModuleRotation());
    }

    public SwerveModuleState getState(){
        return new SwerveModuleState(getModuleVelocity(), getModuleRotation());
    }

    public void stopModule(){   
        angleSetpoint = null;
        speedSetpoint = null;
    }

    private void createSparks(int index){
        switch (index) {
            case 0:
              driveSparkMax = new SparkMax(DriveConstants.frontLeft.DrivePort, MotorType.kBrushless);
              turnSparkMax = new SparkMax(DriveConstants.frontLeft.TurnPort, MotorType.kBrushless);
              absoluteEncoder = new CANcoder(DriveConstants.frontLeft.EncPort);
              isDriveMotorInverted = DriveConstants.frontLeft.DrivemotorReversed;
              isTurnMotorInverted = DriveConstants.frontLeft.TurnmotorReversed;
              Offset = DriveConstants.frontLeft.offset;
              
              break;
            case 1:
              driveSparkMax = new SparkMax(DriveConstants.frontRight.DrivePort, MotorType.kBrushless);
              turnSparkMax = new SparkMax(DriveConstants.frontRight.TurnPort, MotorType.kBrushless);
              absoluteEncoder = new CANcoder(DriveConstants.frontRight.EncPort);
              isDriveMotorInverted = DriveConstants.frontRight.DrivemotorReversed;
              isTurnMotorInverted = DriveConstants.frontRight.TurnmotorReversed;
              Offset = DriveConstants.frontRight.offset;
              
  
              break;
            case 2:
              driveSparkMax = new SparkMax(DriveConstants.backLeft.DrivePort, MotorType.kBrushless);
              turnSparkMax = new SparkMax(DriveConstants.backLeft.TurnPort, MotorType.kBrushless);
              absoluteEncoder = new CANcoder(DriveConstants.backLeft.EncPort);
              isDriveMotorInverted = DriveConstants.backLeft.DrivemotorReversed;
              isTurnMotorInverted = DriveConstants.backLeft.TurnmotorReversed;
              Offset = DriveConstants.backLeft.offset;
              
              break;
            case 3:
              driveSparkMax = new SparkMax(DriveConstants.backRight.DrivePort, MotorType.kBrushless);
              turnSparkMax = new SparkMax(DriveConstants.backRight.TurnPort, MotorType.kBrushless);
              absoluteEncoder = new CANcoder(DriveConstants.backRight.EncPort);
              isDriveMotorInverted = DriveConstants.backRight.DrivemotorReversed;
              isTurnMotorInverted = DriveConstants.backRight.TurnmotorReversed;
              Offset = DriveConstants.backRight.offset;
              
              break;
            default:
              throw new RuntimeException("Invalid module index");
          }

        driveSparkMax.setCANTimeout(250);
        turnSparkMax.setCANTimeout(250);

        enc_drive = driveSparkMax.getEncoder();

        enc_drive.setPosition(0.0);

        enc_turn = turnSparkMax.getEncoder();

          config_drive
        .inverted(isDriveMotorInverted)
        .idleMode(IdleMode.kBrake)
        .smartCurrentLimit(43)
        .voltageCompensation(12);
  
        config_drive.encoder
        .uvwAverageDepth(2)
        .uvwMeasurementPeriod(10);
  
        driveSparkMax.configure(config_drive, com.revrobotics.spark.SparkBase.ResetMode.kResetSafeParameters, PersistMode.kPersistParameters);

        config_turn
        .inverted(isTurnMotorInverted)
        .idleMode(IdleMode.kBrake)
        .smartCurrentLimit(20)
        .voltageCompensation(12);
  
        config_turn.encoder
        .uvwAverageDepth(2)
        .uvwMeasurementPeriod(10);

        turnSparkMax.configure(config_turn, com.revrobotics.spark.SparkBase.ResetMode.kResetSafeParameters, PersistMode.kPersistParameters);

        driveSparkMax.setCANTimeout(0);
        turnSparkMax.setCANTimeout(0);
    }
}
