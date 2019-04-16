package cn.keepbx.jpom.common.forward;

/**
 * @author jiangzeyin
 * @date 2019/4/16
 */
public enum NodeUrl {
    /**
     *
     */
    GetTop("/getTop"),

    ProcessList("/processList"),

    TopSocket("/console/system"),

    WhitelistDirectory_Submit("/system/whitelistDirectory_submit"),

    WhitelistDirectory_data("/system/whitelistDirectory_data"),

    ;

    private String url;

    public String getUrl() {
        return url;
    }

    NodeUrl(String url) {
        this.url = url;
    }
}
