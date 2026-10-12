package com.example.layoutprovider;

import java.util.ArrayList;
import java.util.List;

/**
 * LayoutPlanner 纯函数单元测试（零外部依赖、无 Android 运行时、无真机）。
 * 运行方式：poc/tools/run-tests.sh（复用 build.sh 的 SDK 探测编译 src+test 后执行）。
 * 断言目标（DEBT.md D-01）：esc() 的 XML 转义、render() 的 XML 结构。
 */
public class PlannerTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean cond, String msg) {
        if (cond) {
            passed++;
            System.out.println("  PASS " + msg);
        } else {
            failed++;
            System.out.println("  FAIL " + msg);
        }
    }

    public static void main(String[] args) {
        System.out.println("[1] esc() XML 转义");
        check("".equals(LayoutPlanner.esc(null)), "esc(null) 返回空串");
        check("".equals(LayoutPlanner.esc("")), "esc(\"\") 返回空串");
        check("&amp;&lt;&gt;&quot;&apos;".equals(LayoutPlanner.esc("&<>\"'")),
                "五个 XML 特殊字符全部转义");
        check("a&amp;b".equals(LayoutPlanner.esc("a&b")), "普通 & 单独转义");
        check("plain".equals(LayoutPlanner.esc("plain")), "无特殊字符时输出不变");

        System.out.println("[2] render() XML 结构");
        LayoutPlanner.Plan plan = new LayoutPlanner.Plan();
        List<LayoutPlanner.Entry> apps = new ArrayList<>();
        apps.add(new LayoutPlanner.Entry("com.a.b", "com.a.b/.Main", "标<题>", 0));
        LayoutPlanner.Placement folder = new LayoutPlanner.Placement(
                "folder", 0, 1, 2, 2, 2, "工&作<表>", apps, null, null);
        plan.placements.add(folder);
        plan.hotseat.add(new LayoutPlanner.Entry("com.c.d", "com.c.d/.Home", "c", 0));
        String xml = LayoutPlanner.render(plan, 5, 6);
        check(xml.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"), "以 XML 声明开头");
        check(xml.contains("<workspace>\n"), "包含 <workspace> 开标签");
        check(xml.endsWith("</workspace>\n"), "以 </workspace> 结尾");
        check(xml.contains("titleText=\"工&amp;作&lt;表&gt;\""), "folder 名转义后写入 titleText");
        check(xml.contains("<folder container=\"desktop\" screen=\"0\" x=\"1\" y=\"2\""),
                "folder 槽位坐标按参数写入");
        check(xml.contains("packageName=\"com.a.b\" className=\"com.a.b/.Main\""),
                "folder 子项写入 packageName/className");
        check(xml.contains("<appicon container=\"hotseat\" rank=\"0\" packageName=\"com.c.d\""),
                "hotseat 项以 rank 写入");
        check(!xml.contains("标<题>"), "Entry.label 不入 XML（不会被未转义泄漏）");

        System.out.println("结果: " + passed + " 通过, " + failed + " 失败");
        System.exit(failed == 0 ? 0 : 1);
    }
}
