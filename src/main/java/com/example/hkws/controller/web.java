package com.example.hkws.controller;

import com.example.hkws.CommandManager;
import com.example.hkws.CommandManagerImpl;
import com.example.hkws.DTO.ResultDTO;
import com.example.hkws.DTO.request.*;
import com.example.hkws.DTO.response.ChannelInfoListDTO;
import com.example.hkws.data.CommandTasker;
import com.example.hkws.enumeration.HKPlayContorlEnum;
import com.example.hkws.enumeration.ResultEnum;
import com.example.hkws.exception.GlobalException;
import com.example.hkws.service.window.HCNetSDK;

import com.example.hkws.util.ZipUtils;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import io.swagger.annotations.Api;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.NativeLongByReference;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.swing.table.DefaultTableModel;
import java.io.*;
import java.util.*;

@RestController
@RequestMapping("/camera")
@Api(description = "海康摄像头模块")
@Slf4j
public class web {
    // 如果要打包到linux 记得把HCNetSDK 也要换成 linux版的
    static HCNetSDK hCNetSDK = HCNetSDK.INSTANCE;
//    static PlayCtrl playControl = PlayCtrl.INSTANCE;
    /** 文件存放路径前缀 **/
    @Value("${file.upload.path}")
    private String fileUploadPath;

    /** 文件下载地址前缀 **/
    @Value("${file.download.path}")
    private String fileDownloadPath;

    public static NativeLong g_lVoiceHandle;//全局的语音对讲句柄

    //       设置最多十个视频转码，可以设置大一些，随意的
    private  CommandManager manager=new CommandManagerImpl(10);
    /*windows*/
    private  String winAccountInfo= "admin:linghong2019@192.168.123.200";
//    linux
    private  String linuxAccountInfo= "admin:hik12345@";
//    HCNetSDK.NET_DVR_DEVICEINFO_V30 m_strDeviceInfo;//设备信息
//    HCNetSDK.NET_DVR_IPPARACFG  m_strIpparaCfg;//IP参数
//    HCNetSDK.NET_DVR_CLIENTINFO m_strClientInfo;//用户参数

    boolean bRealPlay;//是否在预览.
//    String m_sDeviceIP;//已登录设备的IP地址

//    NativeLong lUserID;//用户句柄
    NativeLong lPreviewHandle;//预览句柄
    NativeLongByReference m_lPort;//回调预览时播放库端口指针

    NativeLong lAlarmHandle;//报警布防句柄
    NativeLong lListenHandle;//报警监听句柄

    @PostMapping("/login")
    public ResultDTO login(@RequestBody LoginDTO loginDTO, HttpServletRequest request) throws GlobalException {
        boolean initSuc = hCNetSDK.NET_DVR_Init();
        if (initSuc != true)
        {
            return ResultDTO.of(ResultEnum.ERROR).setData("初始化失败");
        }
        String m_sDeviceIP = loginDTO.getIp();//设备ip地址
        HCNetSDK.NET_DVR_DEVICEINFO_V30 m_strDeviceInfo = new HCNetSDK.NET_DVR_DEVICEINFO_V30();
        NativeLong lUserID = hCNetSDK.NET_DVR_Login_V30(m_sDeviceIP,
                (short) loginDTO.getPort(), loginDTO.getAccount(), loginDTO.getPassword(), m_strDeviceInfo);

        long userID = lUserID.longValue();
        if (userID == -1)
        {
            m_sDeviceIP = "";//登录未成功,IP置为空
            System.out.println("Login error: " +  hCNetSDK.NET_DVR_GetLastError());
            return ResultDTO.of(ResultEnum.ERROR).setData("登录失败");
        }
        HttpSession session = request.getSession();
        session.setAttribute("m_sDeviceIP",m_sDeviceIP);
        session.setAttribute("lUserID",lUserID);
        List<String>  channelList =  CreateDeviceChannel(lUserID,m_strDeviceInfo);
        System.out.println("channelList"+channelList);
        return ResultDTO.of(ResultEnum.SUCCESS).setData(channelList);
    }

    /**
     * 获取设备信息
     * @Description:
     * @param loginDTO
     * @param request
     * @Date: 2020/4/21
     * @Time: 16:58
     * @return: com.example.hkws.DTO.ResultDTO<java.util.List<com.example.hkws.DTO.response.ChannelInfoListDTO>>
     */
    @PostMapping("/getDeviceState")
    public ResultDTO<List<ChannelInfoListDTO>> showState(@RequestBody LoginDTO loginDTO, HttpServletRequest request) throws GlobalException {
        boolean initSuc = hCNetSDK.NET_DVR_Init();
        if (initSuc != true)
        {
            return ResultDTO.of(ResultEnum.ERROR).setData("初始化失败");
        }
        String m_sDeviceIP = loginDTO.getIp();//设备ip地址
        HCNetSDK.NET_DVR_DEVICEINFO_V30 m_strDeviceInfo = new HCNetSDK.NET_DVR_DEVICEINFO_V30();
        NativeLong lUserID = hCNetSDK.NET_DVR_Login_V30(m_sDeviceIP,
                (short) loginDTO.getPort(), loginDTO.getAccount(), loginDTO.getPassword(), m_strDeviceInfo);

        long userID = lUserID.longValue();
        if (userID == -1)
        {
            m_sDeviceIP = "";//登录未成功,IP置为空
            System.out.println("Login error: " +  hCNetSDK.NET_DVR_GetLastError());
            return ResultDTO.of(ResultEnum.ERROR).setData("登录失败");
        }
        HttpSession session = request.getSession();
        session.setAttribute("m_sDeviceIP",m_sDeviceIP);
        session.setAttribute("lUserID",lUserID);

        List<ChannelInfoListDTO> channelInfoListDTOList = new ArrayList<>();

        HCNetSDK.NET_DVR_WORKSTATE_V30 m_strWorkState = new HCNetSDK.NET_DVR_WORKSTATE_V30();//调用接口获取设备工作状态
        boolean getDVRConfigSuc = hCNetSDK.NET_DVR_GetDVRWorkState_V30(lUserID, m_strWorkState);
        if (getDVRConfigSuc != true)
        {
            return ResultDTO.of(ResultEnum.ERROR).setData("获取设备状态失败");
        }

        //显示IP通道状态
        //首先获取IP参数,如果IP参数对应通道参数的byEnable为有效,则该通道有效,
        //再从工作参数结构体中取得相关状态参数,模拟通道号:0-31,IP通道号.32-64
        IntByReference ibrBytesReturned = new IntByReference(0);//获取IP接入配置参数
        boolean bRet = false;
        HCNetSDK.NET_DVR_IPPARACFG m_strIpparaCfg = new HCNetSDK.NET_DVR_IPPARACFG();
        m_strIpparaCfg.write();
        Pointer lpIpParaConfig = m_strIpparaCfg.getPointer();
        bRet = hCNetSDK.NET_DVR_GetDVRConfig(lUserID, HCNetSDK.NET_DVR_GET_IPPARACFG, new NativeLong(0), lpIpParaConfig, m_strIpparaCfg.size(), ibrBytesReturned);
        m_strIpparaCfg.read();
//        if (getDVRConfigSuc != true)
//        {//设备不支持,则表示没有IP通道
//            for (int iChannum = 0; iChannum < m_strDeviceInfo.byChanNum; iChannum++)
//            {
//                bChannelEnabled[iChannum] = true;
//            }
//        } else
//        {//包含IP通道
//            for (int iChannum = 0; iChannum < m_strDeviceInfo.byChanNum; iChannum++)
//            {
//                bChannelEnabled[iChannum] = (m_strIpparaCfg.byAnalogChanEnable[iChannum] == 1) ? true : false;
//            }
//        }
        for(int iChannum =0; iChannum < HCNetSDK.MAX_IP_CHANNEL; iChannum++) {
            //判断对应IP通道是否有效
            if (m_strIpparaCfg.struIPChanInfo[iChannum].byEnable == 1) {
                ChannelInfoListDTO channelInfoListDTO = new ChannelInfoListDTO();
                //添加IP通道号
                String sIp = new String(m_strIpparaCfg.struIPDevInfo[iChannum].struIP.sIpV4);
                String channelIp = sIp.replaceAll("[\u0000]", "");

                channelInfoListDTO.setIpAddress(channelIp); //设置IP
                channelInfoListDTO.setByRecordStatic(m_strWorkState.struChanStatic[32 + iChannum].byRecordStatic); //通道是否在录像,0-不录像,1-录像
                channelInfoListDTO.setBySignalStatic(m_strWorkState.struChanStatic[32 + iChannum].bySignalStatic); //连接的信号状态,0-正常,1-信号丢失
                channelInfoListDTO.setByHardwareStatic(m_strWorkState.struChanStatic[32 + iChannum].byHardwareStatic); //通道硬件状态,0-正常,1-异常,例如DSP死掉
                channelInfoListDTO.setDwBitRate(m_strWorkState.struChanStatic[32 + iChannum].dwBitRate); //实际码率
                channelInfoListDTO.setDwLinkNum(m_strWorkState.struChanStatic[32 + iChannum].dwLinkNum); //客户端连接的个数

                channelInfoListDTOList.add(channelInfoListDTO);
            }
        }

        return ResultDTO.of(ResultEnum.SUCCESS).setData(channelInfoListDTOList);
    }

    @PostMapping("/getLiveStream")
    @ApiOperation(value="获取实时视频流",notes = "channelStream:101 代表通道一 主码流。102代表通道一 子码流；子码流比主码流小，但画质会有所下降 ，channelName是通道名，能保证唯一即可")
    public ResultDTO live(@RequestBody LiveDTO liveDTO, HttpServletRequest request, HttpServletResponse response) throws  GlobalException{
        HttpSession session = request.getSession();
        NativeLong lUserID = (NativeLong) session.getAttribute("lUserID");
        if(lUserID.intValue()==-1){
            return ResultDTO.of(ResultEnum.REQUIRE_LOGIN);
        }
        String liveUrl = "";
        String channelName = liveDTO.getChannelName();

        //通过id查询这个任务
        CommandTasker info=manager.query(channelName);
//       如果任务没存在，开启视频流
        if(Objects.isNull(info)){
            //执行原生ffmpeg命令（不包含ffmpeg的执行路径，该路径会从配置文件中自动读取）
            manager.start(channelName, "ffmpeg -re  -rtsp_transport tcp -i \"rtsp://"+winAccountInfo+"/Streaming/Channels/"+liveDTO.getChannelStream()+"?transportmode=unicast\" -f flv -vcodec h264 -vprofile baseline -acodec aac -ar 44100 -strict -2 -ac 1 -f flv -s 640*360 -q 10 \"rtmp://localhost:1935/live/\""+channelName);
        }
        // 如果是window rtmp版就返回 rtmp://localhost:1935/live/\""+channelName
        liveUrl = "rtmp://localhost:1935/live/"+channelName;
        // 下面这个是http-flv版的流
//        liveUrl= "/live?port=1935&app=myapp&stream="+channelName;

        return ResultDTO.of(ResultEnum.SUCCESS).setData(liveUrl);
    }

    @PostMapping("/getHistoryStream")
    @ApiOperation(value="获取实时视频流",notes = "channelStream:101 通道一 码流一，channelName是通道名，能保证唯一即可,跟前端约定就以 history为前缀，避免跟上面的channelName冲突了")
    public ResultDTO history(@RequestBody HistoryDTO historyDTO, HttpServletRequest request, HttpServletResponse response) throws  GlobalException{
        HttpSession session = request.getSession();
        NativeLong lUserID = (NativeLong) session.getAttribute("lUserID");
        if(lUserID.intValue()==-1){
            return ResultDTO.of(ResultEnum.REQUIRE_LOGIN);
        }
        String liveUrl = "";
        String channelName = historyDTO.getChannelName();
        String starttime = historyDTO.getStarttime();
        String endtime = historyDTO.getEndtime();

        //通过id查询这个任务
        CommandTasker info=manager.query(channelName);
//       如果任务没存在，开启视频流
        if(Objects.isNull(info)){
            //执行原生ffmpeg命令（不包含ffmpeg的执行路径，该路径会从配置文件中自动读取）
            manager.start(channelName, "ffmpeg -re  -rtsp_transport tcp -i " +
                    "\"rtsp://"+winAccountInfo+"/Streaming/tracks/"+historyDTO.getChannelStream()+"?transportmode=unicast&startime="+starttime+"&endtime="+endtime+"\" " +
                    "-f flv -vcodec h264 -vprofile baseline -acodec aac -ar 44100 -strict -2 -ac 1 -f flv -s 640*360 -q 10 \"rtmp://localhost:1935/live/\""+channelName);
        }
        // 如果是window rtmp版就返回 rtmp://localhost:1935/live/\""+channelName
        liveUrl = "rtmp://localhost:1935/live/"+channelName;
        // 下面这个是http-flv版的流
//        liveUrl= "/live?port=1935&app=myapp&stream="+channelName;

        return ResultDTO.of(ResultEnum.SUCCESS).setData(liveUrl);
    }

    @PostMapping("/closeLiveStream")
    public ResultDTO closeLive(@RequestBody CloseLiveDTO closeLiveDTO){
        List<String> channelList = closeLiveDTO.getChannelList();
        if(channelList.size()!=0){
            for(String channelName:channelList){
                //       设置最多十个视频转码，可以设置大一些，随意的
                // CommandManager manager=new CommandManagerImpl(10);
                //通过id查询这个任务
                CommandTasker info=manager.query(channelName);
                System.out.println(info);
                //       如果任务存在
                if(!Objects.isNull(info)){
                    manager.stop(channelName);
                }
            }
        }
        return ResultDTO.of(ResultEnum.SUCCESS);
    }


    @PostMapping("/linux/getLiveStream")
    @ApiOperation(value="获取实时视频流",notes = "channelStream:101 代表通道一 主码流。102代表通道一 子码流；子码流比主码流小，但画质会有所下降 ，channelName是通道名，能保证唯一即可")
    public ResultDTO linuxLive(@RequestBody LiveDTO liveDTO, HttpServletRequest request, HttpServletResponse response) throws  GlobalException{
        HttpSession session = request.getSession();
        NativeLong lUserID = (NativeLong) session.getAttribute("lUserID");
        if(lUserID.intValue()==-1){
            return ResultDTO.of(ResultEnum.REQUIRE_LOGIN);
        }
        String liveUrl = "";
        String channelName = liveDTO.getChannelName();
        String ip = liveDTO.getIp();

        //通过id查询这个任务
        CommandTasker info=manager.query(channelName);
//       如果任务没存在，开启视频流
        if(Objects.isNull(info)){
            //执行原生ffmpeg命令（不包含ffmpeg的执行路径，该路径会从配置文件中自动读取）
            manager.start(channelName, "ffmpeg -re  -rtsp_transport tcp -i \"rtsp://"+linuxAccountInfo+ip+"/Streaming/Channels/"+liveDTO.getChannelStream()+"?transportmode=unicast\" -f flv -vcodec h264 -vprofile baseline -acodec aac -ar 44100 -strict -2 -ac 1 -f flv -s 640*360 -q 10 \"rtmp://localhost:1935/live/\""+channelName);
        }
        // 如果是window rtmp版就返回 rtmp://localhost:1935/live/\""+channelName
//        liveUrl = "rtmp://localhost:1935/live/"+channelName;
        // 下面这个是http-flv版的流
        liveUrl= "/live?port=1935&app=live&stream="+channelName;

        return ResultDTO.of(ResultEnum.SUCCESS).setData(liveUrl);
    }

    @PostMapping("/linux/getHistoryStream")
    @ApiOperation(value="获取实时视频流",notes = "channelStream:101 通道一 码流一，channelName是通道名，能保证唯一即可,跟前端约定就以 history为前缀，避免跟上面的channelName冲突了")
    public ResultDTO linuxHistory(@RequestBody HistoryDTO historyDTO, HttpServletRequest request, HttpServletResponse response) throws  GlobalException{
        HttpSession session = request.getSession();
        NativeLong lUserID = (NativeLong) session.getAttribute("lUserID");
        if(lUserID.intValue()==-1){
            return ResultDTO.of(ResultEnum.REQUIRE_LOGIN);
        }
        String liveUrl = "";
        String channelName = historyDTO.getChannelName();
        String starttime = historyDTO.getStarttime();
        String endtime = historyDTO.getEndtime();
        String ip = historyDTO.getIp();

        //通过id查询这个任务
        CommandTasker info=manager.query(channelName);
//       如果任务没存在，开启视频流
        if(Objects.isNull(info)){
            //执行原生ffmpeg命令（不包含ffmpeg的执行路径，该路径会从配置文件中自动读取）
            manager.start(channelName, "ffmpeg -re  -rtsp_transport tcp -i " +
                    "\"rtsp://"+linuxAccountInfo+ip+"/Streaming/tracks/"+historyDTO.getChannelStream()+"?transportmode=unicast&startime="+starttime+"&endtime="+endtime+"\" " +
                    "-f flv -vcodec h264 -vprofile baseline -acodec aac -ar 44100 -strict -2 -ac 1 -f flv -s 640*360 -q 10 \"rtmp://localhost:1935/live/\""+channelName);
        }
        // 如果是window rtmp版就返回 rtmp://localhost:1935/live/\""+channelName
//        liveUrl = "rtmp://localhost:1935/live/"+channelName;
        // 下面这个是http-flv版的流
        liveUrl= "/live?port=1935&app=live&stream="+channelName;

        return ResultDTO.of(ResultEnum.SUCCESS).setData(liveUrl);
    }

    @PostMapping("/linux/closeLiveStream")
    public ResultDTO linuxCloseLive(@RequestBody CloseLiveDTO closeLiveDTO){
        List<String> channelList = closeLiveDTO.getChannelList();
        if(channelList.size()!=0){
            for(String channelName:channelList){
                //       设置最多十个视频转码，可以设置大一些，随意的
                // CommandManager manager=new CommandManagerImpl(10);
                //通过id查询这个任务
                CommandTasker info=manager.query(channelName);
                System.out.println(info);
                //       如果任务存在
                if(!Objects.isNull(info)){
                    manager.stop(channelName);
                }
            }
        }
        return ResultDTO.of(ResultEnum.SUCCESS);
    }

//    下载文件版回放 耗时较长，建议使用rtsp协议版
    @PostMapping("/getVideoUrl")
    public ResultDTO playback(@RequestBody PlayBackConDTO playBackConDTO, HttpServletRequest request, HttpServletResponse response) throws GlobalException, InterruptedException {
        HttpSession session = request.getSession();
        NativeLong lUserID = (NativeLong) session.getAttribute("lUserID");
        String sDeviceIP = (String)session.getAttribute("m_sDeviceIP");
        // =====================按照开始时间和结束时间下载视频 开始====================================
        NativeLong m_lLoadHandle = new NativeLong(-1);
                 if (m_lLoadHandle.intValue() == -1) {
                     HCNetSDK.NET_DVR_TIME struStartTime;
                         HCNetSDK.NET_DVR_TIME struStopTime;

                        struStartTime = new HCNetSDK.NET_DVR_TIME();
                        struStopTime = new HCNetSDK.NET_DVR_TIME();
                         struStartTime.dwYear = (playBackConDTO.getStartYear());// 开始时间
                         struStartTime.dwMonth = (playBackConDTO.getStartMonth());
                        struStartTime.dwDay = playBackConDTO.getStartDay();
                         struStartTime.dwHour = playBackConDTO.getStartHour();
                         struStartTime.dwMinute =playBackConDTO.getStartMinute();
                         struStartTime.dwSecond = playBackConDTO.getStartSecond();

                         struStopTime.dwYear = playBackConDTO.getEndYear();// 结束时间
                         struStopTime.dwMonth = playBackConDTO.getEndMonth();
                         struStopTime.dwDay = playBackConDTO.getEndDay();
                         struStopTime.dwHour = playBackConDTO.getEndHour();
                         struStopTime.dwMinute = playBackConDTO.getEndMinute();
                         struStopTime.dwSecond = playBackConDTO.getEndSecond();
                         int m_iChanShowNum = getChannelNumber(playBackConDTO.getChannelName());// 通道（摄像头IP地址）
                         System.out.println(playBackConDTO.toString());
                         System.out.println("lUserID:"+lUserID);
                         System.out.println("m_iChanShowNum:"+m_iChanShowNum);
                         String fileName = sDeviceIP+"/"+m_iChanShowNum+"/";
                         String fileTitleN = struStartTime.toStringTitle() + "-" + System.currentTimeMillis(); //文件名
                         String fileTitle = fileTitleN + ".mp4"; //文件名加文件类型后缀
                         String sFileName = fileUploadPath +fileName + fileTitle; //文件存放地址
                         String zipFilePath = fileUploadPath + fileName + fileTitleN + ".zip"; //压缩文件路径
                         String downloadPath = fileDownloadPath + fileName + fileTitleN + ".zip"; //文件下载地址
                         System.out.println(sFileName);
                         File file = new File(fileUploadPath +fileName);
                         if (!file.exists()) {
                             file.mkdirs();
                         }

                         // 视频下载调用 下载的文件是mpeg-ps 非标准的mpeg-4
                        m_lLoadHandle = hCNetSDK.NET_DVR_GetFileByTime(lUserID, new NativeLong(m_iChanShowNum), struStartTime,
                                         struStopTime, sFileName);
                        System.out.println("m_lLoadHandle："+m_lLoadHandle);
                         if (m_lLoadHandle.intValue() >= 0) {
//                             开始下载
                             hCNetSDK.NET_DVR_PlayBackControl(m_lLoadHandle, HCNetSDK.NET_DVR_PLAYSTART, 0, null);
                             IntByReference nPos = new IntByReference(0);
                             while (m_lLoadHandle.intValue()>=0){
//                                 获取下载进度
                                 hCNetSDK.NET_DVR_PlayBackControl(m_lLoadHandle, HCNetSDK.NET_DVR_PLAYGETPOS, 0, nPos);
                                 if (nPos.getValue() == 100) {
                                     hCNetSDK.NET_DVR_StopGetFile(m_lLoadHandle);
                                     m_lLoadHandle.setValue(-1);
                                     System.out.println("按时间下载结束!");
                                     // 将文件压成zip压缩包
                                     try {
                                         ZipUtils.toZip(new String[]{sFileName} , zipFilePath, Boolean.TRUE);
                                     } catch (Exception e) {
                                         e.printStackTrace();
                                         return ResultDTO.of(ResultEnum.ERROR.getCode(), "压缩zip出错");
                                     }
                                     Integer error =  hCNetSDK.NET_DVR_GetLastError();
                                     System.out.println("last error " +error);
                                 }
                                 if (nPos.getValue() > 100) {
                                     Integer error =  hCNetSDK.NET_DVR_GetLastError();
                                     System.out.println("下载失败");// 按时间
                                     System.out.println("last error " +error);
                                     return ResultDTO.of(ResultEnum.ERROR).setData(error);

                                 }
                                 Thread.sleep(500);
                             }
                             return ResultDTO.of(ResultEnum.SUCCESS).setData(downloadPath);


                             // System.out.println("视频下载成功！");
                             } else {
                                 Integer error =  hCNetSDK.NET_DVR_GetLastError();
                                 System.out.println("下载失败");// 按时间
                                 System.out.println("last error " +error);
                                 return ResultDTO.of(ResultEnum.ERROR).setData(error);
                             }
                     }
        return ResultDTO.of(ResultEnum.ERROR);
    }

    @RequestMapping(value = "/videoPlay", method = RequestMethod.GET)
    public void videoPlay(HttpServletRequest request, HttpServletResponse response,@RequestParam String url) {
//        String path = request.getServletContext().getRealPath(url);
        String path = fileUploadPath+url;
        BufferedInputStream bis = null;
        System.out.println("url"+url);
        System.out.println("path"+path);
        try {
            File file = new File(path);
            if (file.exists()) {
                long p = 0L;
                long toLength = 0L;
                long contentLength = 0L;
                int rangeSwitch = 0; // 0,从头开始的全文下载；1,从某字节开始的下载（bytes=27000-）；2,从某字节开始到某字节结束的下载（bytes=27000-39000）
                long fileLength;
                String rangBytes = "";
                fileLength = file.length();

                // get file content
                InputStream ins = new FileInputStream(file);
                bis = new BufferedInputStream(ins);

                // tell the client to allow accept-ranges
                response.reset();
                response.setHeader("Accept-Ranges", "bytes");

                // client requests a file block download start byte
                String range = request.getHeader("Range");
                if (range != null && range.trim().length() > 0 && !"null".equals(range)) {
                    response.setStatus(javax.servlet.http.HttpServletResponse.SC_PARTIAL_CONTENT);
                    rangBytes = range.replaceAll("bytes=", "");
                    if (rangBytes.endsWith("-")) { // bytes=270000-
                        rangeSwitch = 1;
                        p = Long.parseLong(rangBytes.substring(0, rangBytes.indexOf("-")));
                        contentLength = fileLength - p; // 客户端请求的是270000之后的字节（包括bytes下标索引为270000的字节）
                    } else { // bytes=270000-320000
                        rangeSwitch = 2;
                        String temp1 = rangBytes.substring(0, rangBytes.indexOf("-"));
                        String temp2 = rangBytes.substring(rangBytes.indexOf("-") + 1, rangBytes.length());
                        p = Long.parseLong(temp1);
                        toLength = Long.parseLong(temp2);
                        contentLength = toLength - p + 1; // 客户端请求的是 270000-320000 之间的字节
                    }
                } else {
                    contentLength = fileLength;
                }

                // 如果设设置了Content-Length，则客户端会自动进行多线程下载。如果不希望支持多线程，则不要设置这个参数。
                // Content-Length: [文件的总大小] - [客户端请求的下载的文件块的开始字节]
//                response.setHeader("Content-Length", new Long(contentLength).toString());

                // 断点开始
                // 响应的格式是:
                // Content-Range: bytes [文件块的开始字节]-[文件的总大小 - 1]/[文件的总大小]
                if (rangeSwitch == 1) {
                    String contentRange = new StringBuffer("bytes ").append(new Long(p).toString()).append("-")
                            .append(new Long(fileLength - 1).toString()).append("/")
                            .append(new Long(fileLength).toString()).toString();
                    response.setHeader("Content-Range", contentRange);
                    bis.skip(p);
                } else if (rangeSwitch == 2) {
                    String contentRange = range.replace("=", " ") + "/" + new Long(fileLength).toString();
                    response.setHeader("Content-Range", contentRange);
                    bis.skip(p);
                } else {
                    String contentRange = new StringBuffer("bytes ").append("0-").append(fileLength - 1).append("/")
                            .append(fileLength).toString();
                    response.setHeader("Content-Range", contentRange);
                }

                String fileName = file.getName();
                response.setContentType("application/octet-stream");
                response.addHeader("Content-Disposition", "attachment;filename=" + fileName);

                OutputStream out = response.getOutputStream();
                int n = 0;
                long readLength = 0;
                int bsize = 1024;
                byte[] bytes = new byte[bsize];
                if (rangeSwitch == 2) {
                    // 针对 bytes=27000-39000 的请求，从27000开始写数据
                    while (readLength <= contentLength - bsize) {
                        n = bis.read(bytes);
                        readLength += n;
                        out.write(bytes, 0, n);
                    }
                    if (readLength <= contentLength) {
                        n = bis.read(bytes, 0, (int) (contentLength - readLength));
                        out.write(bytes, 0, n);
                    }
                } else {
                    while ((n = bis.read(bytes)) != -1) {
                        out.write(bytes, 0, n);
                    }
                }
                out.flush();
                out.close();
                bis.close();
            }
        } catch (IOException ie) {
            // 忽略 ClientAbortException 之类的异常
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @PostMapping("/playControl")
    public ResultDTO playControl(@RequestBody PlayControlDTO playControlDTO, HttpServletRequest request) throws GlobalException{
        HttpSession session = request.getSession();
        NativeLong lUserID = (NativeLong) session.getAttribute("lUserID");
        int iCannleNum = getChannelNumber(playControlDTO.getChannelName());
        HKPlayContorlEnum controlEnum = HKPlayContorlEnum.valueOf(playControlDTO.getCommand());
        Boolean playHandle = false;
        System.out.println("controlEnum.getCode():"+controlEnum.getCode());
        System.out.println("playControlDTO:"+playControlDTO.toString());
//        开始控制
        playHandle = hCNetSDK.NET_DVR_PTZControl_Other(lUserID,new NativeLong(iCannleNum),controlEnum.getCode(),0);
        if(playHandle==false){
            Integer error =  hCNetSDK.NET_DVR_GetLastError();
            System.out.println("控制失败");// 按时间
            System.out.println("last error " +error);
            return ResultDTO.of(ResultEnum.ERROR).setData(error);
        }
        //        停止控制
        playHandle = hCNetSDK.NET_DVR_PTZControl_Other(lUserID,new NativeLong(iCannleNum),controlEnum.getCode(),1);
        if(playHandle==false){
            Integer error =  hCNetSDK.NET_DVR_GetLastError();
            System.out.println("控制失败");// 按时间
            System.out.println("last error " +error);
            return ResultDTO.of(ResultEnum.ERROR).setData(error);
        }
        return ResultDTO.of(ResultEnum.SUCCESS);
    }


    /*************************************************
     函数:    CreateDeviceTree
     函数描述:建立设备通道数
     *************************************************/
    private List<String> CreateDeviceChannel(NativeLong lUserID, HCNetSDK.NET_DVR_DEVICEINFO_V30 m_strDeviceInfo)
    {
        List<String>  channelList = new ArrayList<>();
        IntByReference ibrBytesReturned = new IntByReference(0);//获取IP接入配置参数
        boolean bRet = false;

        HCNetSDK.NET_DVR_IPPARACFG m_strIpparaCfg = new HCNetSDK.NET_DVR_IPPARACFG();
        m_strIpparaCfg.write();
        Pointer lpIpParaConfig = m_strIpparaCfg.getPointer();
        bRet = hCNetSDK.NET_DVR_GetDVRConfig(lUserID, HCNetSDK.NET_DVR_GET_IPPARACFG, new NativeLong(0), lpIpParaConfig, m_strIpparaCfg.size(), ibrBytesReturned);
        m_strIpparaCfg.read();

        if (!bRet)
        {
            //设备不支持,则表示没有IP通道
            for (int iChannum = 0; iChannum < m_strDeviceInfo.byChanNum; iChannum++)
            {
                channelList.add("Camera" + (iChannum + m_strDeviceInfo.byStartChan));

            }
        }
        else
        {
            //设备支持IP通道
            for (int iChannum = 0; iChannum < m_strDeviceInfo.byChanNum; iChannum++)
            {
                if(m_strIpparaCfg.byAnalogChanEnable[iChannum] == 1)
                {
                    channelList.add("Camera" + (iChannum + m_strDeviceInfo.byStartChan));
                }
            }
            for(int iChannum =0; iChannum < HCNetSDK.MAX_IP_CHANNEL; iChannum++)
                if (m_strIpparaCfg.struIPChanInfo[iChannum].byEnable == 1)
                {
                    String sIp = new String(m_strIpparaCfg.struIPDevInfo[iChannum].struIP.sIpV4);
                    System.out.println("sIp:" + sIp);
                    String channel = "IPCamera-" + (iChannum + m_strDeviceInfo.byStartChan) + "-" + sIp;
                    channel = channel.replaceAll("[\u0000]", "");
                    channelList.add(channel);
                }
        }
        return channelList;
    }
    /*************************************************
     函数:    getChannelNumber
     函数描述:从设备树获取通道号
     *************************************************/
    int getChannelNumber(String sChannelName)
    {
        int iChannelNum = -1;


            //获取选中的通道名,对通道名进行分析:

            if(sChannelName.charAt(0) == 'C')//Camara开头表示模拟通道
            {
                //子字符串中获取通道号
                iChannelNum = Integer.parseInt(sChannelName.substring(6));
            }
            else
            {
                if(sChannelName.charAt(0) == 'I')//IPCamara开头表示IP通道
                {
                    String[] val = sChannelName.split("-");
                    //子字符创中获取通道号,IP通道号要加32
//                    iChannelNum = Integer.parseInt(sChannelName.substring(8)) + 32;
                    iChannelNum = Integer.parseInt(val[1]) + HCNetSDK.MAX_IP_CHANNEL;
                }
                else
                {
                    return -1;
                }
            }

        return iChannelNum;
    }










}
