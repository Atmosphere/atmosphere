/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 */
package org.atmosphere.samples.tictactoe;

public class TTTGame {

    final public static int X = 10;
    final public static int O = 1;
    int[] board = {0, 0, 0, 0, 0, 0, 0, 0, 0};
    int turnNum = 0;
    int[][] wins = {{0, 1, 2}, {3, 4, 5,}, {6, 7, 8}, {0, 3, 6},
        {1, 4, 7}, {2, 5, 8}, {0, 4, 8}, {2, 4, 6}};
    int winner = -1;

    //return false if cell is an invalid move
    public boolean turn(int cell) {
        if (cell < 0 || cell > 8) {
            return false; // invalid move
        }
        if (winner != -1) {
            return false;
        }
        if (board[cell] != 0) {
            return false; // invalid move
        }
        turnNum++;
        if (turnNum % 2 == 1) { //then X
            board[cell] = X;
        } else { // else O
            board[cell] = O;
        }
        return true;
    }

    // return 
    private int whoseTurn() {
        if (turnNum == 0 || turnNum % 2 == 0) {
            return X;
        } else {
            return O;
        }
    }

    private boolean done() {
        return (turnNum > 8);
    }

    // return -1 for no win, 0 for tie, 1 for x win, 2 for o win
    public int win() {
        if (winner != -1) {
            return winner;
        }
        for (int i = 0; i < 8; i++) {
            int winSum = board[wins[i][0]] + board[wins[i][1]] + board[wins[i][2]];
            if (winSum == 3) {
                winner = 2;
            } else if (winSum == 30) {
                winner = 1;
            }
        }
        if (winner == -1 && turnNum > 8) {
            winner = 0;
        }
        return winner;
    }

    private int[] getBoard() {
        return board;
    }

    /**
     * Create a JSON representation of the game state.  It will look something like this:
     * <code>
     * { "win": "-1", "board": ["0","0","0","0","0","0","0","0","0"] }
     * </code>
     * @return json 
     */
    public String getJSON() {
        String response = "{" +
                "\"win\": \"" + win() + "\", \"board\": [";
        for (int i = 0; i < 9; i++) {
            response = response + "\"" + board[i] + "\"";
            if (i < 8) {  // no trailing comma
                response = response + ",";
            }
        }
        response = response + "]," + "\"turn\": " + whoseTurn() + " }";
        return response;
    }
}
