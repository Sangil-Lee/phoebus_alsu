package org.phoebus.applications.git;

/** A version of the repository content, used to compare, restore and merge files */
sealed interface Revision
{
    /** Files in the work tree */
    record WorkTree() implements Revision
    {
        @Override
        public String toString()
        {
            return Messages.WorkTree;
        }
    }

    /** Staged files, index stage 0 */
    record Index() implements Revision
    {
        @Override
        public String toString()
        {
            return Messages.Index;
        }
    }

    /** Commit
     *  @param id Full commit id
     *  @param label Branch, tag or abbreviated id for display
     */
    record Commit(String id, String label) implements Revision
    {
        @Override
        public String toString()
        {
            return label;
        }
    }

    /** Index stage of a conflict
     *  @param stage 1 for base, 2 for ours, 3 for theirs
     */
    record Stage(int stage) implements Revision
    {
        @Override
        public String toString()
        {
            return switch (stage)
            {
                case 1  -> Messages.MergeBase;
                case 2  -> Messages.MergeOurs;
                default -> Messages.MergeTheirs;
            };
        }
    }

    /** Work tree */
    static final Revision WORK_TREE = new WorkTree();

    /** Index */
    static final Revision INDEX = new Index();
}
