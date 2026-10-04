/**
 * LeetCode's binary tree node, verbatim. Lives in the default package and on the workspace
 * classpath so pasted solutions compile without edits.
 */
public class TreeNode {
    public int val;
    public TreeNode left;
    public TreeNode right;
    public TreeNode() {}
    public TreeNode(int val) { this.val = val; }
    public TreeNode(int val, TreeNode left, TreeNode right) {
        this.val = val; this.left = left; this.right = right;
    }
}
