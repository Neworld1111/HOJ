package top.hcode.hoj.controller.oj;

import cn.hutool.core.lang.Validator;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.dao.*;
import top.hcode.hoj.pojo.dto.LoginDto;
import top.hcode.hoj.pojo.dto.RegisterDto;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.pojo.entity.*;
import top.hcode.hoj.pojo.vo.ConfigVo;
import top.hcode.hoj.pojo.vo.UserHomeVo;
import top.hcode.hoj.pojo.vo.UserRolesVo;
import top.hcode.hoj.service.UserInfoService;
import top.hcode.hoj.service.impl.EmailServiceImpl;
import top.hcode.hoj.service.impl.UserAcproblemServiceImpl;
import top.hcode.hoj.service.impl.UserRecordServiceImpl;
import top.hcode.hoj.utils.Constants;
import top.hcode.hoj.utils.IpUtils;
import top.hcode.hoj.utils.JwtUtils;
import top.hcode.hoj.utils.RedisUtils;

import javax.mail.MessagingException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * @Author: Himit_ZH
 * @Date: 2020/10/23 12:00
 * @Description:账户处理控制类，负责处理登录请求和注册请求等有关账号的操作
 */
@RestController
@RequestMapping("/api")
public class AccountController {

    @Autowired
    private EmailServiceImpl emailService;

    @Autowired
    private RedisUtils redisUtils;

    @Autowired
    private UserInfoService userInfoDao;

    @Autowired
    private UserRoleMapper userRoleDao;

    @Autowired
    private UserRecordMapper userRecordDao;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserAcproblemServiceImpl userAcproblemService;

    @Autowired
    private SessionMapper sessionDao;

    @Autowired
    private ConfigVo configVo;

    /**
     * @MethodName getRegisterCode
     * @Params * @param null
     * @Description 调用邮件服务，发送注册流程的6位随机验证码
     * @Return
     * @Since 2020/10/26
     */
    @RequestMapping(value = "/get-register-code", method = RequestMethod.GET)
    public CommonResult getRegisterCode(@RequestParam(value = "email", required = true) String email) throws MessagingException {
        if (!configVo.getRegister()) { // 需要判断一下网站是否开启注册
            return CommonResult.errorResponse("对不起！本站暂未开启注册功能！", CommonResult.STATUS_ACCESS_DENIED);
        }
        String numbers = RandomUtil.randomNumbers(6); // 随机生成6位数字的组合
        redisUtils.set(email, numbers, 5 * 60);//默认验证码有效5分钟
        emailService.sendCode(email, numbers);
        return CommonResult.successResponse(MapUtil.builder()
                .put("email", email)
                .put("expire", 5 * 60)
                .map(), "验证码已发送至指定邮箱");
    }

    /**
     * @MethodName checkUsernameOrEmail
     * @Params * @param null
     * @Description 检验用户名和邮箱是否存在
     * @Return
     * @Since 2020/11/5
     */

    @RequestMapping(value = "/check-username-or-email", method = RequestMethod.POST)
    public CommonResult checkUsernameOrEmail(@RequestBody Map<String, Object> data) throws MessagingException {
        String email = (String) data.get("email");
        String username = (String) data.get("username");
        boolean rightEmail = false;
        boolean rightUsername = false;
        if (!StringUtils.isEmpty(email)) {
            boolean isEmail = Validator.isEmail(email);
            if (!isEmail) {
                rightEmail = false;
            } else {
                QueryWrapper<UserInfo> wrapper = new QueryWrapper<UserInfo>().eq("email", email);
                UserInfo user = userInfoDao.getOne(wrapper);
                if (user != null) {
                    rightEmail = true;
                } else {
                    rightEmail = false;
                }
            }
        }
        if (!StringUtils.isEmpty(username)) {
            QueryWrapper<UserInfo> wrapper = new QueryWrapper<UserInfo>().eq("username", username);
            UserInfo user = userInfoDao.getOne(wrapper);
            if (user != null) {
                rightUsername = true;
            } else {
                rightUsername = false;
            }
        }
        return CommonResult.successResponse(MapUtil.builder().put("email", rightEmail)
                .put("username", rightUsername).map(), "检验成功"
        );
    }

    /**
     * @MethodName applyResetPassword
     * @Params * @param null
     * @Description 发送重置密码的链接邮件
     * @Return
     * @Since 2020/11/6
     */
    @PostMapping("/apply-reset-password")
    public CommonResult applyResetPassword(@RequestBody Map<String, Object> data) throws MessagingException {
        String email = (String) data.get("email");
        String username = (String) data.get("username");
        if (StringUtils.isEmpty(email) || StringUtils.isEmpty(username)) {
            return CommonResult.errorResponse("用户名或邮箱不能为空");
        }
        QueryWrapper<UserInfo> wrapper = new QueryWrapper<UserInfo>().eq("email", email).eq("username", username);
        UserInfo user = userInfoDao.getOne(wrapper);
        if (user == null) {
            return CommonResult.errorResponse("用户名和该邮箱不匹配");
        }
        String code = IdUtil.simpleUUID().substring(0, 21); // 随机生成20位数字与字母的组合
        redisUtils.set(username, code, 10 * 60);//默认链接有效10分钟
        emailService.sendResetPassword(username, code, email);
        return CommonResult.successResponse(null, "重置密码邮件已发送至指定邮箱，请稍稍等待一会。");
    }


    /**
     * @MethodName resetPassword
     * @Params * @param null
     * @Description 用户重置密码
     * @Return
     * @Since 2020/11/6
     */

    @PostMapping("/reset-password")
    public CommonResult resetPassword(@RequestBody Map<String, Object> data) {
        String username = (String) data.get("username");
        String password = (String) data.get("password");
        String code = (String) data.get("code");
        if (StringUtils.isEmpty(password) || StringUtils.isEmpty(username) || StringUtils.isEmpty(code)) {
            return CommonResult.errorResponse("用户名,新密码或验证码不能为空");
        }
        if (!redisUtils.hasKey(username)) {
            return CommonResult.errorResponse("重置密码链接不存在或已过期，请重新发送重置邮件");
        }
        if (!redisUtils.get(username).equals(code)) { //验证码判断
            return CommonResult.errorResponse("重置密码的链接验证码不正确，请重新发送重置邮件");
        }

        boolean result = userInfoDao.update(new UserInfo().setPassword(SecureUtil.md5(password)),
                new UpdateWrapper<UserInfo>().eq("username", username));
        if (!result) {
            return CommonResult.errorResponse("重置密码失败", CommonResult.STATUS_ERROR);
        }
        redisUtils.del(username);
        return CommonResult.successResponse(null, "重置密码成功");
    }


    /**
     * @MethodName register
     * @Params * @param RegisterDto
     * @Description 注册逻辑，具体参数请看RegisterDto类
     * @Return
     * @Since 2020/10/24
     */
    @PostMapping("/register")
    @Transactional
    public CommonResult register(@Validated @RequestBody RegisterDto registerDto) {

        if (!configVo.getRegister()) { // 需要判断一下网站是否开启注册
            return CommonResult.errorResponse("对不起！本站暂未开启注册功能！", CommonResult.STATUS_ACCESS_DENIED);
        }

        if (!redisUtils.hasKey(registerDto.getEmail())) {
            return CommonResult.errorResponse("验证码不存在或已过期");
        }
        if (!redisUtils.get(registerDto.getEmail()).equals(registerDto.getCode())) { //验证码判断
            return CommonResult.errorResponse("验证码不正确");
        }
        String uuid = IdUtil.simpleUUID();
        //为新用户设置uuid
        registerDto.setUuid(uuid);
        registerDto.setPassword(SecureUtil.md5(registerDto.getPassword())); // 将密码MD5加密写入数据库
        //往user_info表插入数据
        int result1 = userInfoDao.addUser(registerDto);
        //往user_role表插入数据
        int result2 = userRoleDao.insert(new UserRole().setRoleId(1002L).setUid(uuid));
        //往user_record表插入数据
        int result3 = userRecordDao.insert(new UserRecord().setUid(uuid));
        if (result1 == 1 && result2 == 1 && result3 == 1) {
            redisUtils.del(registerDto.getEmail());
            return CommonResult.successResponse(null, "谢谢你的注册！");
        } else {
            return CommonResult.errorResponse("注册失败！", CommonResult.STATUS_ERROR); // 插入数据库失败，返回500
        }
    }


    /**
     * @MethodName login
     * @Params * @param LoginDto
     * @Description 处理登录逻辑
     * @Return CommonResult
     * @Since 2020/10/24
     */
    @PostMapping("/login")
    public CommonResult login(@Validated @RequestBody LoginDto loginDto, HttpServletResponse response, HttpServletRequest request) {
        // 去掉账号密码首尾的空格
        loginDto.setPassword(loginDto.getPassword().trim());
        loginDto.setUsername(loginDto.getUsername().trim());

        UserRolesVo userRoles = userRoleDao.getUserRoles(null, loginDto.getUsername());
        Assert.notNull(userRoles, "用户名不存在，请注意大小写！");
        if (!userRoles.getPassword().equals(SecureUtil.md5(loginDto.getPassword()))) {
            return CommonResult.errorResponse("密码不正确");
        }
        if (userRoles.getStatus() != 0) {
            return CommonResult.errorResponse("该账户已被封禁，请联系管理员进行处理。");
        }

        String jwt = jwtUtils.generateToken(userRoles.getUid());
        response.setHeader("Authorization", jwt); //放到信息头部
        response.setHeader("Access-Control-Expose-Headers", "Authorization");
        // 查询角色列表
        List<String> rolesList = new LinkedList<>();
        userRoles.getRoles()
                .forEach(role -> rolesList.add(role.getRole()));

        // 会话记录
        sessionDao.insert(new Session().setUid(userRoles.getUid())
                .setIp(IpUtils.getUserIpAddr(request)).setUserAgent(request.getHeader("User-Agent")));

        return CommonResult.successResponse(MapUtil.builder()
                .put("uid", userRoles.getUid())
                .put("username", userRoles.getUsername())
                .put("nickname", userRoles.getNickname())
                .put("avatar", userRoles.getAvatar())
                .put("email", userRoles.getEmail())
                .put("number", userRoles.getNumber())
                .put("school", userRoles.getSchool())
                .put("course", userRoles.getCourse())
                .put("signature", userRoles.getSignature())
                .put("realname", userRoles.getRealname())
                .put("roleList", rolesList)
                .map(), "登录成功！"
        );
    }


    /**
     * @MethodName logout
     * @Params * @param null
     * @Description 退出逻辑，将jwt在redis中清除，下次需要再次登录。
     * @Return CommonResult
     * @Since 2020/10/24
     */
    @GetMapping("/logout")
    @RequiresAuthentication
    public CommonResult logout() {
        SecurityUtils.getSubject().logout();
        return CommonResult.successResponse(null, "登出成功！");
    }


    /**
     * @MethodName getUserHomeInfo
     * @Params * @param uid
     * @Description 前端userHome用户个人主页的数据请求，主要是返回解决题目数，AC的题目列表，提交总数，AC总数，Rating分，
     * @Return CommonResult
     * @Since 2021/01/07
     */
    @GetMapping("/get-user-home-info")
    @RequiresAuthentication
    public CommonResult getUserHomeInfo(@RequestParam(value = "uid", required = false) String uid, HttpServletRequest request) {
        HttpSession session = request.getSession();
        UserRolesVo userRolesVo = (UserRolesVo) session.getAttribute("userInfo");
        // 如果没有uid，默认查询当前登录用户的
        if (uid == null) {
            uid = userRolesVo.getUid();
        }
        UserHomeVo userHomeInfo = userRecordDao.getUserHomeInfo(uid);
        QueryWrapper<UserAcproblem> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("uid", uid).select("distinct pid");
        List<Long> pidList = new LinkedList<>();
        List<UserAcproblem> acProblemList = userAcproblemService.list(queryWrapper);
        acProblemList.forEach(acProblem -> {
            pidList.add(acProblem.getPid());
        });
        userHomeInfo.setSolvedList(pidList);
        return CommonResult.successResponse(userHomeInfo, "查询成功！");
    }


    /**
     * @MethodName changePassword
     * @Params * @param null
     * @Description 修改密码的操作，连续半小时内修改密码错误5次，则需要半个小时后才可以再次尝试修改密码
     * @Return
     * @Since 2021/1/8
     */

    @PostMapping("/change-password")
    @RequiresAuthentication
    public CommonResult changePassword(@RequestBody Map params, HttpServletRequest request) {
        String oldPassword = (String) params.get("oldPassword");
        String newPassword = (String) params.get("newPassword");

        // 数据可用性判断
        if (StringUtils.isEmpty(oldPassword) || StringUtils.isEmpty(newPassword)) {
            return CommonResult.errorResponse("请求参数不能为空！");
        }
        // 获取当前登录的用户
        HttpSession session = request.getSession();
        UserRolesVo userRolesVo = (UserRolesVo) session.getAttribute("userInfo");

        // 如果已经被锁定半小时不能修改
        String lockKey = Constants.Account.CODE_CHANGE_PASSWORD_LOCK + userRolesVo.getUid();
        // 统计失败的key
        String countKey = Constants.Account.CODE_CHANGE_PASSWORD_FAIL + userRolesVo.getUid();

        HashMap<String, Object> resp = new HashMap<>();
        if (redisUtils.hasKey(lockKey)) {
            long expire = redisUtils.getExpire(lockKey);
            Date now = new Date();
            long minute = expire / 60;
            long second = expire % 60;
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            resp.put("code", 403);
            Date afterDate = new Date(now.getTime() + expire * 1000);
            String msg = "由于您多次修改密码失败，修改密码功能已锁定，请在" + minute + "分" + second + "秒后(" + formatter.format(afterDate) + ")再进行尝试！";
            resp.put("msg", msg);
            return CommonResult.successResponse(resp, "修改密码失败！");
        }
        // 与当前登录用户的密码进行比较判断
        if (userRolesVo.getPassword().equals(SecureUtil.md5(oldPassword))) { // 如果相同，则进行修改密码操作
            UpdateWrapper<UserInfo> updateWrapper = new UpdateWrapper<>();
            updateWrapper.set("password", SecureUtil.md5(newPassword))// 数据库用户密码全部用md5加密
                    .eq("uuid", userRolesVo.getUid());
            boolean result = userInfoDao.update(updateWrapper);
            if (result) {
                resp.put("code", 200);
                resp.put("msg", "修改密码成功！您将于5秒钟后退出进行重新登录操作！");
                // 清空记录
                redisUtils.del(countKey);
                return CommonResult.successResponse(resp, "修改密码成功！");
            } else {
                return CommonResult.errorResponse("系统错误：修改密码失败！", CommonResult.STATUS_ERROR);
            }
        } else { // 如果不同，则进行记录，当失败次数达到5次，半个小时后才可重试
            Integer count = (Integer) redisUtils.get(countKey);
            if (count == null) {
                redisUtils.set(countKey, 1, 60 * 30); // 三十分钟不尝试，该限制会自动清空消失
                count = 0;
            } else if (count < 5) {
                redisUtils.incr(countKey, 1);
            }
            count++;
            if (count == 5) {
                redisUtils.del(countKey); // 清空统计
                redisUtils.set(lockKey, "lock", 60 * 30); // 设置锁定更改
            }
            resp.put("code", 400);
            resp.put("msg", "原始密码错误！您已累计修改失败" + count + "次...");
            return CommonResult.successResponse(resp, "修改密码失败！");
        }
    }

    /**
     * @MethodName changeEmail
     * @Params * @param null
     * @Description 修改邮箱的操作，连续半小时内密码错误5次，则需要半个小时后才可以再次尝试修改
     * @Return
     * @Since 2021/1/9
     */
    @PostMapping("/change-email")
    @RequiresAuthentication
    public CommonResult changeEmail(@RequestBody Map params, HttpServletRequest request) {
        String password = (String) params.get("password");
        String oldEmail = (String) params.get("oldEmail");
        String newEmail = (String) params.get("newEmail");
        // 数据可用性判断
        if (StringUtils.isEmpty(password) || StringUtils.isEmpty(newEmail) || StringUtils.isEmpty(oldEmail)) {
            return CommonResult.errorResponse("请求参数不能为空！");
        }
        // 获取当前登录的用户
        HttpSession session = request.getSession();
        UserRolesVo userRolesVo = (UserRolesVo) session.getAttribute("userInfo");

        // 如果已经被锁定半小时不能修改
        String lockKey = Constants.Account.CODE_CHANGE_EMAIL_LOCK + userRolesVo.getUid();
        // 统计失败的key
        String countKey = Constants.Account.CODE_CHANGE_EMAIL_FAIL + userRolesVo.getUid();

        HashMap<String, Object> resp = new HashMap<>();
        if (redisUtils.hasKey(lockKey)) {
            long expire = redisUtils.getExpire(lockKey);
            Date now = new Date();
            long minute = expire / 60;
            long second = expire % 60;
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            resp.put("code", 403);
            Date afterDate = new Date(now.getTime() + expire * 1000);
            String msg = "由于您多次修改邮箱失败，修改邮箱功能已锁定，请在" + minute + "分" + second + "秒后(" + formatter.format(afterDate) + ")再进行尝试！";
            resp.put("msg", msg);
            return CommonResult.successResponse(resp, "修改邮箱失败！");
        }
        // 与当前登录用户的密码进行比较判断
        if (userRolesVo.getPassword().equals(SecureUtil.md5(password))) { // 如果相同，则进行修改操作
            UpdateWrapper<UserInfo> updateWrapper = new UpdateWrapper<>();
            updateWrapper.set("email", newEmail)
                    .eq("uuid", userRolesVo.getUid());
            boolean result = userInfoDao.update(updateWrapper);
            if (result) {
                resp.put("code", 200);
                resp.put("msg", "修改邮箱成功！您将于5秒钟后退出进行重新登录操作！");
                // 清空记录
                redisUtils.del(countKey);
                return CommonResult.successResponse(resp, "修改邮箱成功！");
            } else {
                return CommonResult.errorResponse("系统错误：修改邮箱失败！", CommonResult.STATUS_ERROR);
            }
        } else { // 如果不同，则进行记录，当失败次数达到5次，半个小时后才可重试
            Integer count = (Integer) redisUtils.get(countKey);
            if (count == null) {
                redisUtils.set(countKey, 1, 60 * 30); // 三十分钟不尝试，该限制会自动清空消失
                count = 0;
            } else if (count < 5) {
                redisUtils.incr(countKey, 1);
            }
            count++;
            if (count == 5) {
                redisUtils.del(countKey); // 清空统计
                redisUtils.set(lockKey, "lock", 60 * 30); // 设置锁定更改
            }
            resp.put("code", 400);
            resp.put("msg", "原始密码错误！您已累计修改失败" + count + "次...");
            return CommonResult.successResponse(resp, "修改邮箱失败！");
        }
    }

}