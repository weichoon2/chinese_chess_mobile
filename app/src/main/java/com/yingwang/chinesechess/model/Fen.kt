package com.yingwang.chinesechess.model

/**
 * Xiangqi FEN as Pikafish reads it: ranks from black's back rank down, red upper case
 * (K A B N R C P), black lower case, then `w` or `b` for the side to move. The endgame
 * studies are stored this way and a saved game keeps the position it started from in it.
 */
object Fen {
    private val TO_LETTER = mapOf(
        PieceType.GENERAL to 'k', PieceType.ADVISOR to 'a', PieceType.ELEPHANT to 'b',
        PieceType.HORSE to 'n', PieceType.CHARIOT to 'r', PieceType.CANNON to 'c', PieceType.SOLDIER to 'p'
    )
    // E and H are the WXF spellings of the elephant and horse; accept them too.
    private val FROM_LETTER = TO_LETTER.entries.associate { (type, letter) -> letter to type } +
        mapOf('e' to PieceType.ELEPHANT, 'h' to PieceType.HORSE)

    fun parse(fen: String): Board {
        val fields = fen.trim().split(Regex("\\s+"))
        val ranks = fields[0].split('/')
        require(ranks.size == Board.ROWS) { "FEN needs ${Board.ROWS} ranks: $fen" }
        val pieces = mutableListOf<Piece>()
        for ((row, rank) in ranks.withIndex()) {
            var col = 0
            for (ch in rank) {
                if (ch.isDigit()) {
                    col += ch - '0'
                } else {
                    val type = FROM_LETTER[ch.lowercaseChar()] ?: error("Unknown piece '$ch' in $fen")
                    val color = if (ch.isUpperCase()) PieceColor.RED else PieceColor.BLACK
                    pieces.add(Piece(type, color, Position(row, col)))
                    col++
                }
            }
            require(col == Board.COLS) { "Rank $row of $fen has $col files" }
        }
        val side = if (fields.getOrNull(1) == "b") PieceColor.BLACK else PieceColor.RED
        return Board.createFromPieces(pieces, side)
    }

    fun format(board: Board): String {
        val sb = StringBuilder()
        for (row in 0 until Board.ROWS) {
            var empty = 0
            for (col in 0 until Board.COLS) {
                val piece = board.getPiece(Position(row, col))
                if (piece == null) {
                    empty++
                    continue
                }
                if (empty > 0) sb.append(empty)
                empty = 0
                val letter = TO_LETTER.getValue(piece.type)
                sb.append(if (piece.color == PieceColor.RED) letter.uppercaseChar() else letter)
            }
            if (empty > 0) sb.append(empty)
            if (row < Board.ROWS - 1) sb.append('/')
        }
        sb.append(if (board.currentPlayer == PieceColor.RED) " w" else " b")
        return sb.toString()
    }
}
